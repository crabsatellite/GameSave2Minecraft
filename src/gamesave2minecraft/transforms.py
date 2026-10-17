"""Canonical affine transforms. Shear is retained until a target explicitly handles it."""
from __future__ import annotations
import math
from .scene import SceneError

IDENTITY=(1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.)

def multiply(a,b):
    return tuple(sum(a[r*4+k]*b[k*4+c] for k in range(4)) for r in range(4) for c in range(4))

def trs(position,rotation,scale):
    length=math.sqrt(sum(x*x for x in rotation)); x,y,z,w=(v/length for v in rotation)
    r=[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),position[0],
       2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),position[1],
       2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y),position[2],0.,0.,0.,1.]
    for row in range(3):
        for col in range(3):r[row*4+col]*=scale[col]
    return tuple(r)

def point(matrix,p):
    return tuple(sum(matrix[r*4+c]*p[c] for c in range(3))+matrix[r*4+3] for r in range(3))

def world_matrices(instances):
    rows={r['id']:r for r in instances};resolved={};active=set()
    def visit(key):
        if key in resolved:return resolved[key]
        if key in active:raise SceneError('Instance hierarchy cycle')
        active.add(key);r=rows[key];local=trs(r['position'],r['rotation'],r['scale'])
        result=multiply(visit(r['parent']),local) if r.get('parent') else local
        resolved[key]=result;active.remove(key);return result
    for key in rows:visit(key)
    return resolved

def axis_aligned_bounds(matrix,bounds):
    corners=[point(matrix,(x,y,z)) for x in [bounds['from'][0],bounds['to'][0]] for y in [bounds['from'][1],bounds['to'][1]] for z in [bounds['from'][2],bounds['to'][2]]]
    return {'from':[min(p[i] for p in corners) for i in range(3)],'to':[max(p[i] for p in corners) for i in range(3)]}
