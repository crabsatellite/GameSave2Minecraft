from __future__ import annotations
import argparse,json
from pathlib import Path
from .scene import validate
from .compiler import compile_scene,verify_package
from .minecraft import emit,verify_emission

def main(argv=None):
    p=argparse.ArgumentParser(description='Validate, compile and read back normalized game-save scenes.')
    s=p.add_subparsers(dest='command',required=True)
    for name in ['validate','compile','emit-minecraft']:
        q=s.add_parser(name);q.add_argument('scene',type=Path);q.add_argument('--resources',type=Path)
        if name in {'compile','emit-minecraft'}:q.add_argument('--output',type=Path,required=True)
    q=s.add_parser('verify');q.add_argument('package',type=Path)
    q=s.add_parser('verify-minecraft');q.add_argument('package',type=Path)
    a=p.parse_args(argv)
    if a.command=='verify':result=verify_package(a.package)
    elif a.command=='verify-minecraft':result=verify_emission(a.package)
    else:
        scene=json.loads(a.scene.read_text(encoding='utf-8'));validate(scene,resources=a.resources)
        if a.command=='emit-minecraft':
            if a.resources is None:p.error('emit-minecraft requires --resources')
            result=emit(scene,a.resources,a.output)
        else:result=compile_scene(scene,a.output,resources=a.resources) if a.command=='compile' else {'passed':True,'instances':len(scene['instances'])}
    print(json.dumps(result,indent=2));return 0

if __name__=='__main__':raise SystemExit(main())
