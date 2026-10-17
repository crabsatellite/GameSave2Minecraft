"""Deterministic scene planning; resources remain independently auditable small files."""
from __future__ import annotations
import copy
import hashlib
import json
import math
import uuid
from pathlib import Path
from .scene import canonical,digest,validate,relative_file,require
from .transforms import world_matrices,axis_aligned_bounds
from .rules import acceptance_matrix

def compile_scene(scene: dict, output: Path, *, resources: Path | None = None, chunk_rows: int = 128, maximum_file_bytes: int = 2_000_000) -> dict:
    validate(scene,resources=resources)
    require(1 <= chunk_rows <= 10000,"Invalid plan partition")
    require(not output.exists(),"Output must be a fresh owned directory")
    matrices=world_matrices(scene['instances']);assets={a['id']:a for a in scene['assets']}
    rows=[]
    for source in scene['instances']:
        a=assets[source['asset']];matrix=matrices[source['id']]
        rows.append({'id':str(uuid.uuid5(uuid.NAMESPACE_URL,scene['id']+':'+source['id'])),'source_id':source['id'],'asset':a['id'],'world_matrix':list(matrix),'bounds':axis_aligned_bounds(matrix,a['bounds']),'behavior':a['behavior'],'state':copy.deepcopy(source.get('state',{}))})
    rows.sort(key=lambda r:r['source_id'])
    payloads={};parts=[]
    def partition(batch):
        content=canonical({'instances':batch})
        if len(content)>maximum_file_bytes:
            require(len(batch)>1,"An instance exceeds file budget; split its bounded data explicitly")
            half=len(batch)//2;partition(batch[:half]);partition(batch[half:]);return
        name=f'plans/part_{len(parts):05}.json';payloads[name]=content;parts.append(name)
    for start in range(0,len(rows),chunk_rows):partition(rows[start:start+chunk_rows])
    asset_parts=[]
    def asset_partition(batch):
        data=canonical({'assets':batch})
        if len(data)>maximum_file_bytes:
            require(len(batch)>1,'Asset metadata exceeds file budget; move geometry to resources')
            half=len(batch)//2;asset_partition(batch[:half]);asset_partition(batch[half:]);return
        name=f'assets/part_{len(asset_parts):05}.json';payloads[name]=data;asset_parts.append(name)
    asset_partition(sorted(scene['assets'],key=lambda a:a['id']))
    payloads['catalog.json']=canonical({'parts':asset_parts,'asset_count':len(scene['assets'])})
    payloads['acceptance.json']=canonical(acceptance_matrix(scene))
    payloads['index.json']=canonical({'schema':'gamesave2minecraft.plan/1','scene':scene['id'],'source_scene_sha256':digest(scene),'instance_count':len(rows),'parts':parts,'excluded':scene['coverage']['excluded']})
    if resources is not None:
        needed=set()
        for a in scene['assets']:
            for mesh in {a['mesh'],a.get('growing_mesh',a['mesh'])}:
                path='meshes/'+mesh+'.json';p=relative_file(path,resources);payloads['resources/'+path]=p.read_bytes()
                m=json.loads(p.read_text(encoding='utf-8'))
                needed.update(g['texture'] for g in m['groups'])
        for name in sorted(needed):payloads['resources/'+name]=relative_file(name,resources).read_bytes()
        for record in scene['evidence']['asset_inventory']['records']:
            if record['status']=='mapped':
                for target in record['targets']:payloads['resources/'+target]=relative_file(target,resources).read_bytes()
    payloads['asset-accounting.json']=canonical(scene['evidence']['asset_inventory'])
    for path,data in payloads.items():require(len(data)<=maximum_file_bytes,'Resource exceeds file budget: '+path)
    manifest={'schema':1,'files':{p:{'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()} for p,b in sorted(payloads.items())},'complete_source_accounting':True,'extracted_asset_count':scene['evidence']['asset_inventory']['expected'],'source_scene_sha256':digest(scene)}
    output.mkdir(parents=True)
    try:
        for path,data in payloads.items():
            p=output/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
        (output/'manifest.json').write_bytes(canonical(manifest))
    except BaseException:
        # A partial output never receives a passing readback; callers retain it
        # for diagnosis and choose a fresh output for retry.
        raise
    verify_package(output)
    return manifest

def verify_package(root: Path) -> dict:
    manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
    expected=set(manifest['files'])|{'manifest.json'}
    actual={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    require(actual==expected,'Package file inventory differs from manifest')
    for name,entry in manifest['files'].items():
        data=relative_file(name,root).read_bytes()
        require(len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'],'Package hash mismatch: '+name)
    index=json.loads((root/'index.json').read_text(encoding='utf-8'))
    rows=[row for name in index['parts'] for row in json.loads(relative_file(name,root).read_text(encoding='utf-8'))['instances']]
    require(len(rows)==index['instance_count'] and len({r['id'] for r in rows})==len(rows),'Plan identity/coverage mismatch')
    catalog=json.loads((root/'catalog.json').read_text(encoding='utf-8'))
    assets=[a for name in catalog['parts'] for a in json.loads(relative_file(name,root).read_text(encoding='utf-8'))['assets']]
    keys={a['id'] for a in assets}
    require(len(assets)==len(keys)==catalog['asset_count'],'Asset catalog identity/coverage mismatch')
    require(all(r['asset'] in keys for r in rows),'Target plan contains an unresolved asset')
    return {'passed':True,'files':len(expected),'instances':len(rows),'source_scene_sha256':index['source_scene_sha256']}
