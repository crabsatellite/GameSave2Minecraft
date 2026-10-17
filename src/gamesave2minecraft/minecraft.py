"""Emit metadata and a client pack for the included Minecraft 1.21.1 backend."""
from __future__ import annotations
import copy,gzip,json,math,uuid,hashlib
from pathlib import Path
from .scene import canonical,digest,require,validate,relative_file
from .transforms import world_matrices,trs

def decompose(matrix):
    columns=[[matrix[r*4+c] for r in range(3)] for c in range(3)]
    scale=[math.sqrt(sum(x*x for x in col)) for col in columns]
    require(all(s>1e-8 for s in scale),'Degenerate target transform')
    unit=[[x/s for x in col] for col,s in zip(columns,scale)]
    require(all(abs(sum(unit[a][r]*unit[b][r] for r in range(3)))<1e-5 for a,b in [(0,1),(0,2),(1,2)]),'Bake sheared geometry and interaction anchors before this target emission')
    det=sum(unit[0][r]*(unit[1][(r+1)%3]*unit[2][(r+2)%3]-unit[1][(r+2)%3]*unit[2][(r+1)%3]) for r in range(3))
    require(det>0,'Bake reflected geometry, winding and interaction anchors before target emission')
    m=[[unit[c][r] for c in range(3)] for r in range(3)];trace=sum(m[i][i] for i in range(3))
    if trace>0:
        s=math.sqrt(trace+1)*2;q=[(m[2][1]-m[1][2])/s,(m[0][2]-m[2][0])/s,(m[1][0]-m[0][1])/s,s/4]
    else:
        i=max(range(3),key=lambda j:m[j][j]);j=(i+1)%3;k=(i+2)%3
        s=math.sqrt(max(0,1+m[i][i]-m[j][j]-m[k][k]))*2;q=[0.,0.,0.,0.]
        q[i]=s/4;q[j]=(m[i][j]+m[j][i])/s;q[k]=(m[i][k]+m[k][i])/s;q[3]=(m[k][j]-m[j][k])/s
    position=[matrix[3],matrix[7],matrix[11]]
    rebuilt=trs(position,q,scale)
    require(max(abs(a-b) for a,b in zip(matrix,rebuilt))<1e-4,'Target transform decomposition drift')
    return position,q,scale

def emit(scene,resources: Path,output: Path):
    validate(scene,resources=resources)
    require(not output.exists(),'Minecraft emission output must be fresh')
    ns='gamesave2mc';catalog=[];placements=[];matrices=world_matrices(scene['instances'])
    payload={}
    for asset in scene['assets']:
        a={'id':asset['id'],'label':asset.get('label',asset['id']),'behavior':asset['behavior'],
           'collision':asset['collision'],'bounds':asset['bounds'],'mesh':asset['mesh']}
        if 'socket' in asset:a['seat']=asset['socket']
        if asset['behavior']=='plant':a.update(dormantMesh=asset['growing_mesh'],growTicks=asset['grow_ticks'],harvestItem=asset.get('target_harvest_item','minecraft:wheat'))
        if asset['behavior']=='door':
            require(asset['hinge']==[0,0,0] and asset['open_degrees']==90,'Normalize door hinge to local origin and 90 degree target policy before emission')
        catalog.append(a)
        for key in {asset['mesh'],asset.get('growing_mesh',asset['mesh'])}:
            raw=json.loads(relative_file('meshes/'+key+'.json',resources).read_text(encoding='utf-8'))
            mesh=copy.deepcopy(raw)
            for group in mesh['groups']:
                texture=group['texture'];data=relative_file(texture,resources).read_bytes()
                payload['resource-pack/assets/'+ns+'/'+texture]=data;group['texture']=ns+':'+texture
                if asset['behavior']=='light':group['emissive']=True
            payload['resource-pack/assets/'+ns+'/meshes/'+key+'.json.gz']=gzip.compress(canonical(mesh),mtime=0)
    candidates=[]
    for item in scene['instances']:
        position,q,scale=decompose(matrices[item['id']]);state=item.get('state',{})
        slots=state.get('inventory_slots',0);require(slots<=4096,'Source inventory exceeds current backend capacity; add paging support before emission')
        row={'targetId':str(uuid.uuid5(uuid.NAMESPACE_URL,scene['id']+':'+item['id'])),'sourceEntity':item['id'],'asset':item['asset'],'position':position,'quaternion':q,'scale':scale,'active':state.get('active',False),'inventorySlots':slots,'inventory':[],'sourceState':state}
        for lot in state.get('inventory',[]):row['inventory'].append({**lot,'name':lot.get('name',lot['source_id']),'category':lot.get('category','source')})
        asset=next(a for a in scene['assets'] if a['id']==item['asset'])
        if asset['behavior']=='plant':row['remainingGrowthTicks']=state.get('remaining_growth_ticks',asset['grow_ticks'])
        if asset.get('walkable'):
            require(all(abs(a-b)<1e-6 for a,b in zip(q,[0,0,0,1])),'Supply measured floor candidates for rotated support geometry')
            bounds=asset['bounds'];candidates.append([position[0]+(bounds['from'][0]+bounds['to'][0])/2*scale[0],position[1]+bounds['to'][1]*scale[1],position[2]+(bounds['from'][2]+bounds['to'][2])/2*scale[2]])
        placements.append(row)
    candidates=scene.get('floor_candidates',candidates)
    require(candidates,'A target scene needs measured support candidates for safe entry')
    scene_id=scene['id'];base='generated/data/'+ns+'/scenes/'+scene_id+'/'
    parts=[]
    for start in range(0,len(placements),128):
        name=f'part_{len(parts):05}.json';parts.append(name);payload[base+name]=canonical({'placements':placements[start:start+128]})
    payload[base+'index.json']=canonical({'placementCount':len(placements),'files':parts,'entryAnchor':scene.get('entry_anchor',candidates[0]),'floorCandidates':candidates,'source_scene':scene_id})
    payload['generated/data/'+ns+'/scenes/index.json']=canonical({'scenes':[scene_id]})
    payload['generated/assets/'+ns+'/model_catalog.json']=canonical({'assets':catalog})
    labels={'itemGroup.'+ns:'GameSave2Minecraft',ns+'.modelpack.name':'Local scene models',ns+'.source_lot':'%s × %s'}
    for asset in catalog:
        payload['generated/assets/'+ns+'/models/item/'+asset['id']+'.json']=canonical({'parent':'minecraft:builtin/entity'})
        labels['item.'+ns+'.'+asset['id']]=asset['label']
        labels['component.'+ns+'.'+asset['id']]=asset['label']
    payload['generated/assets/'+ns+'/lang/en_us.json']=canonical(labels)
    payload['resource-pack/pack.mcmeta']=canonical({'pack':{'pack_format':34,'description':'Locally compiled GameSave2Minecraft scene resources'}})
    for key,data in payload.items():require(len(data)<50_000_000,'Target file exceeds the Git payload limit: '+key)
    output.mkdir(parents=True)
    for key,data in payload.items():p=output/key;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    manifest={'schema':'gamesave2minecraft.minecraft/1','source_scene_sha256':digest(scene),'target':'minecraft-1.21.1-neoforge','scene':scene_id,'instances':len(placements),'assets':len(catalog),'files':{key:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for key,data in sorted(payload.items())}}
    (output/'emission.json').write_bytes(canonical(manifest))
    verify_emission(output)
    return {'passed':True,'scene':scene_id,'instances':len(placements),'assets':len(catalog),'files':len(payload)+1,'scope':'Minecraft 1.21.1 NeoForge target emission; runtime acceptance is separate'}

def verify_emission(root):
    m=json.loads((root/'emission.json').read_text(encoding='utf-8'))
    require(m.get('schema')=='gamesave2minecraft.minecraft/1','Unsupported target emission schema')
    expected=set(m['files'])|{'emission.json'}
    actual={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    require(actual==expected,'Target emission file inventory mismatch')
    for key,entry in m['files'].items():
        data=relative_file(key,root).read_bytes()
        require(len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'],'Target emission hash mismatch: '+key)
    return {'passed':True,'scene':m['scene'],'instances':m['instances'],'assets':m['assets'],'files':len(expected)}
