"""Validate the shared world contract before any target files are emitted."""
from __future__ import annotations
import hashlib
import json
import math
import re
from pathlib import Path, PurePosixPath
from typing import Any

ID = re.compile(r"^[a-z0-9_][a-z0-9_./-]{0,127}$")
BEHAVIORS = {"static", "door", "seat", "bed", "storage", "light", "plant", "workstation"}

class SceneError(ValueError):
    pass

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SceneError(message)

def canonical(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(",", ":"))+"\n").encode("utf-8")

def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()

def vector(value: Any, n: int, label: str) -> list[float]:
    require(isinstance(value, list) and len(value) == n, f"{label}: expected {n} numbers")
    require(all(isinstance(x, (int,float)) and not isinstance(x,bool) and math.isfinite(x) for x in value), f"{label}: non-finite or non-numeric value")
    return [float(x) for x in value]

def identifier(value: Any, label: str) -> str:
    require(isinstance(value,str) and bool(ID.fullmatch(value)) and ".." not in value.split("/") and "//" not in value, f"{label}: invalid identifier")
    return value

def relative_file(value: str, root: Path) -> Path:
    p = PurePosixPath(value)
    require(not p.is_absolute() and ".." not in p.parts and "\\" not in value and ":" not in value, "Unsafe resource path")
    result = (root / p).resolve()
    require(result.is_relative_to(root.resolve()), "Resource escapes package root")
    return result

def validate(scene: dict, *, resources: Path | None = None) -> dict:
    require(isinstance(scene,dict) and scene.get("schema") == "gamesave2minecraft.scene/1", "Unsupported scene schema")
    identifier(scene.get("id"), "scene id")
    require(scene.get("coordinates") == {"unit_metres":1,"up":"y","handedness":"right"}, "Adapter must normalize coordinates to metres, Y-up, right-handed")
    assets = scene.get("assets"); rows = scene.get("instances")
    require(isinstance(assets,list) and assets and isinstance(rows,list), "Scene needs asset definitions and instances")
    definitions = {}
    for asset in assets:
        key = identifier(asset.get("id"), "asset id")
        require(key not in definitions, "Duplicate asset id: "+key)
        definitions[key] = asset
        require(asset.get("behavior") in BEHAVIORS, "Unmapped interaction behavior: "+key)
        for bound in asset.get("collision",[]):
            lo=vector(bound.get("from"),3,"collision from"); hi=vector(bound.get("to"),3,"collision to")
            require(all(a < b for a,b in zip(lo,hi)), "Degenerate collision box: "+key)
        bounds=asset.get("bounds",{})
        lo=vector(bounds.get("from"),3,"visual from"); hi=vector(bounds.get("to"),3,"visual to")
        require(all(a < b for a,b in zip(lo,hi)), "Degenerate visual bounds: "+key)
        behavior=asset["behavior"]
        if behavior in {"seat","bed"}:vector(asset.get("socket"),3,"interaction socket")
        if behavior == "door":
            vector(asset.get("hinge"),3,"door hinge")
            require(isinstance(asset.get("open_degrees"),(int,float)) and not isinstance(asset['open_degrees'],bool) and math.isfinite(asset['open_degrees']) and 0 < abs(asset["open_degrees"]) <= 180, "Door rotation missing")
        if behavior == "plant":
            require(isinstance(asset.get("grow_ticks"),int) and not isinstance(asset['grow_ticks'],bool) and asset["grow_ticks"] > 0, "Plant growth duration missing")
            require(asset.get("growing_mesh") and asset.get("mesh"), "Plant requires both growth appearances")
        identifier(asset.get("mesh"), "mesh id")
        if resources is not None:
            for mesh in {asset["mesh"],asset.get("growing_mesh",asset["mesh"])}:
                path=relative_file("meshes/"+mesh+".json",resources)
                require(path.is_file(), "Missing mesh: "+mesh)
                validate_mesh(json.loads(path.read_text(encoding="utf-8")), resources)
    ids={}
    for row in rows:
        key=identifier(row.get("id"),"instance id")
        require(key not in ids,"Duplicate instance id: "+key); ids[key]=row
        require(row.get("asset") in definitions,"Unresolved asset reference: "+key)
        vector(row.get("position"),3,"position"); q=vector(row.get("rotation"),4,"rotation"); scale=vector(row.get("scale"),3,"scale")
        require(sum(x*x for x in q) > 1e-12,"Zero quaternion")
        require(all(abs(x)>1e-8 for x in scale),"Zero scale requires an explicit exclusion policy")
        state=row.get("state",{}); require(isinstance(state,dict),"Instance state must be an object")
        if definitions[row['asset']]['behavior'] in {'door','plant','light'}:
            require(isinstance(state.get('active'),bool),'Active interaction state must be explicit boolean data')
        if 'remaining_growth_ticks' in state:
            ticks=state['remaining_growth_ticks']
            require(isinstance(ticks,int) and not isinstance(ticks,bool) and ticks>=0,'Invalid remaining growth duration')
        count=state.get("inventory_slots",0)
        require(isinstance(count,int) and not isinstance(count,bool) and count >= 0,"Invalid source inventory capacity")
        slots=set()
        for lot in state.get("inventory",[]):
            slot=lot.get("slot"); amount=lot.get("amount")
            require(isinstance(slot,int) and not isinstance(slot,bool) and 0 <= slot < count and slot not in slots,"Duplicate or out-of-range source slot")
            require(isinstance(amount,int) and not isinstance(amount,bool) and 0 < amount <= 2**63-1,"Invalid source lot amount")
            require(isinstance(lot.get("source_id"),str) and bool(lot["source_id"]),"Missing source item identity")
            slots.add(slot)
        canonical(state)
    # Detect cycles and missing parents even when child instances precede parents.
    visited=set(); visiting=set()
    def visit(key):
        require(key not in visiting,"Instance hierarchy cycle: "+key)
        if key in visited:return
        visiting.add(key);parent=ids[key].get("parent")
        if parent is not None:
            require(parent in ids,"Missing parent: "+str(parent));visit(parent)
        visiting.remove(key);visited.add(key)
    for key in ids:visit(key)
    coverage=scene.get("coverage",{})
    expected=coverage.get("expected_instances")
    require(isinstance(expected,int) and not isinstance(expected,bool) and expected >= 0,"Expected source-instance count is required")
    excluded=coverage.get("excluded",[])
    require(isinstance(excluded,list) and all(isinstance(e,dict) and e.get("source_id") and e.get("reason") for e in excluded),"Every exclusion needs source identity and reason")
    require(len(rows)+len(excluded)==expected,"Source coverage does not reconcile")
    excluded_ids=[str(e["source_id"]) for e in excluded]
    require(len(excluded_ids)==len(set(excluded_ids)) and not set(excluded_ids)&set(ids),"Duplicate or included/excluded source identity")
    require(isinstance(scene.get("evidence"),dict) and scene["evidence"].get("adapter"),"Source adapter/evidence identity is required")
    inventory=scene['evidence'].get('asset_inventory',{})
    total=inventory.get('expected');records=inventory.get('records')
    require(isinstance(total,int) and not isinstance(total,bool) and total>=0 and isinstance(records,list),"Extracted asset inventory is required")
    require(len(records)==total,"Extracted asset inventory does not reconcile")
    accounted=set()
    for record in records:
        require(isinstance(record,dict) and isinstance(record.get('source_id'),str) and bool(record['source_id']),"Extracted asset needs source identity")
        require(record['source_id'] not in accounted,"Duplicate extracted asset identity")
        accounted.add(record['source_id'])
        require(record.get('status') in {'mapped','excluded'},"Unresolved extracted asset")
        if record['status']=='excluded':
            require(isinstance(record.get('reason'),str) and bool(record['reason'].strip()),"Excluded asset needs a reason")
        else:
            targets=record.get('targets')
            require(isinstance(targets,list) and targets and all(isinstance(t,str) and t for t in targets),"Mapped asset needs target resources")
            for target in targets:
                path=relative_file(target,resources or Path('.'))
                if resources is not None:require(path.is_file(),"Mapped asset resource missing: "+target)
    return scene

def validate_mesh(mesh: dict, root: Path | None = None) -> None:
    require(isinstance(mesh,dict) and isinstance(mesh.get("groups"),list) and mesh["groups"],"Mesh needs material groups")
    for group in mesh["groups"]:
        positions=group.get("positions"); indices=group.get("indices")
        require(isinstance(positions,list) and len(positions)>=9 and len(positions)%3==0,"Invalid mesh positions")
        vector(positions,len(positions),"vertices")
        n=len(positions)//3
        vector(group.get("normals"),n*3,"normals");vector(group.get("uvs"),n*2,"UVs")
        require(isinstance(indices,list) and len(indices)>=3 and len(indices)%3==0 and all(isinstance(x,int) and not isinstance(x,bool) and 0 <= x < n for x in indices),"Invalid mesh topology")
        texture=group.get("texture")
        require(isinstance(texture,str),"Material texture missing")
        if root is not None:require(relative_file(texture,root).is_file(),"Missing texture: "+texture)
