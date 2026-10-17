"""Deterministically author a small source-game fixture, including every visual byte."""
from __future__ import annotations
import argparse,gzip,json,struct,sys,zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'src'))
from gamesave2minecraft.scene import canonical

def png(rgb):
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    rows=[]
    for y in range(16):
        row=bytearray([0])
        for x in range(16):
            delta=8 if (x//4+y//4)%2 else -8
            row.extend(max(0,min(255,c+delta)) for c in rgb);row.append(255)
        rows.append(bytes(row))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows),9))+chunk(b'IEND',b'')

def box_mesh(bounds,texture):
    x0,y0,z0=bounds['from'];x1,y1,z1=bounds['to'];positions=[];normals=[];uv=[];indices=[]
    faces=[([(x0,y0,z0),(x0,y1,z0),(x1,y1,z0),(x1,y0,z0)],(0,0,-1)),
           ([(x1,y0,z1),(x1,y1,z1),(x0,y1,z1),(x0,y0,z1)],(0,0,1)),
           ([(x0,y0,z1),(x0,y1,z1),(x0,y1,z0),(x0,y0,z0)],(-1,0,0)),
           ([(x1,y0,z0),(x1,y1,z0),(x1,y1,z1),(x1,y0,z1)],(1,0,0)),
           ([(x0,y1,z0),(x0,y1,z1),(x1,y1,z1),(x1,y1,z0)],(0,1,0)),
           ([(x0,y0,z1),(x0,y0,z0),(x1,y0,z0),(x1,y0,z1)],(0,-1,0))]
    for corners,normal in faces:
        base=len(positions)//3
        for p in corners:positions.extend(p);normals.extend(normal)
        uv.extend([0,1,0,0,1,0,1,1]);indices.extend([base,base+1,base+2,base,base+2,base+3])
    return {'schema':1,'origin':[0,0,0],'scale':1,'groups':[{'texture':texture,'positions':positions,'normals':normals,'uvs':uv,'indices':indices,'emissive':False,'wind':False}]}

def component_boxes(key,size):
    def b(x,y,z,w,h,d):return {'from':[x,y,z],'to':[x+w,y+h,z+d]}
    legs=[b(x,0,z,.1,.42,.1) for x in [.06,.64] for z in [.06,.64]]
    if key=='chair':return legs+[b(0,.42,0,.8,.1,.8),b(0,.52,.69,.8,.48,.11)]
    if key=='bed':return [b(0,.12,0,1.5,.16,2),b(.04,.28,.04,1.42,.19,1.92),b(.15,.47,.12,1.2,.08,.35)]+[b(x,0,z,.13,.12,.13) for x in [.05,1.32] for z in [.05,1.82]]
    if key=='door':return [b(0,0,0,1,2,.08),b(.05,.12,.08,.9,.1,.045),b(.05,1.78,.08,.9,.1,.045),b(.83,.9,.08,.09,.1,.045)]
    if key=='chest':return [b(0,.05,0,1,.62,.6),b(0,.67,0,1,.13,.6),b(.43,.48,0,.14,.25,.06)]
    if key=='lamp':return [b(.11,0,.11,.08,.3,.08),b(.02,.3,.02,.26,.22,.26),b(0,0,0,.3,.04,.3),b(.09,.52,.09,.12,.08,.12)]
    if key=='crop':return [b(.22,0,.22,.06,1,.06)]+[b(.04,.2+i*.2,.17,.42,.06,.16) for i in range(4)]
    if key=='bench':return [b(0,.78,0,1.3,.12,.7)]+[b(x,0,z,.12,.78,.12) for x in [.04,1.14] for z in [.04,.54]]
    return [b(0,0,0,*size)]

def assembled_mesh(boxes,texture,wind=False):
    result=box_mesh(boxes[0],texture);group=result['groups'][0]
    for box in boxes[1:]:
        next_group=box_mesh(box,texture)['groups'][0];base=len(group['positions'])//3
        for key in ['positions','normals','uvs']:group[key].extend(next_group[key])
        group['indices'].extend(i+base for i in next_group['indices'])
    group['wind']=wind
    return result

def outputs():
    files={};assets=[];instances=[]
    specs=[('door','door',[1,2,.125],[111,72,43]),('chair','seat',[.8,1,.8],[123,82,51]),
           ('bed','bed',[1.5,.55,2],[112,57,88]),('chest','storage',[1,.8,.6],[145,91,41]),
           ('lamp','light',[.3,.6,.3],[220,190,110]),('crop','plant',[.5,1,.5],[65,121,45]),
           ('bench','workstation',[1.3,.9,.7],[98,104,108]),('floor','static',[2,.125,2],[102,106,111])]
    for i,(key,behavior,size,color) in enumerate(specs):
        bounds={'from':[0,0,0],'to':size};texture='textures/'+key+'.png';boxes=component_boxes(key,size)
        a={'id':key,'label':'Demo '+key,'behavior':behavior,'bounds':bounds,'collision':boxes if behavior!='plant' else [],'mesh':key}
        if key=='floor':a['walkable']=True
        if behavior in {'seat','bed'}:a['socket']=[size[0]/2,.5,size[2]/2]
        if behavior=='door':a.update(hinge=[0,0,0],open_degrees=90)
        if behavior=='plant':
            a.update(growing_mesh='crop_growing',grow_ticks=1200)
            files['client/meshes/crop_growing.json']=canonical(box_mesh({'from':[0,0,0],'to':[.25,.2,.25]},texture))
        assets.append(a);files['client/meshes/'+key+'.json']=canonical(assembled_mesh(boxes,texture,behavior=='plant'));files['client/'+texture]=png(color)
        state={'active':False if behavior=='door' else True,'inventory_slots':0,'inventory':[]}
        if behavior=='storage':state.update(inventory_slots=54,inventory=[{'slot':37,'source_id':'demo:ore','name':'Demo ore','amount':100000,'category':'material'}])
        instances.append({'id':'object_'+key,'asset':key,'position':[i*2,-.125 if key=='floor' else 0,0],'rotation':[0,0,0,1],'scale':[1,1,1],'state':state})
    for i in range(7):instances.append({'id':'support_'+str(i),'asset':'floor','position':[i*2,-.125,0],'rotation':[0,0,0,1],'scale':[1,1,1],'state':{}})
    files['client/catalog.json']=canonical({'assets':assets})
    files['client/version.json']=canonical({'engine':'original-fixture','version':'1'})
    files['source.save.gz']=gzip.compress(canonical({'format':'original-demo-save/1','placed_objects':instances}),mtime=0)
    return files

def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.add_argument('--output',type=Path,default=ROOT/'examples/demo');a=p.parse_args();data=outputs()
    if a.check:
        actual={f.relative_to(a.output).as_posix() for f in a.output.rglob('*') if f.is_file()}
        if actual!=set(data) or any((a.output/k).read_bytes()!=v for k,v in data.items()):raise SystemExit('Original demo output differs from generator')
    else:
        for key,value in data.items():path=a.output/key;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(value)
    print(f'Original demo: {len(data)} reproducible files')
if __name__=='__main__':main()
