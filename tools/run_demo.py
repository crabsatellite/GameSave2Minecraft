"""One original save -> normalized scene -> portable plan -> Minecraft emission."""
from pathlib import Path
import argparse,json,sys
ROOT=Path(__file__).resolve().parents[1];sys.path[:0]=[str(ROOT/'src'),str(ROOT/'examples')]
from gamesave2minecraft.adapters import SourceInputs
from gamesave2minecraft.compiler import compile_scene,verify_package
from gamesave2minecraft.minecraft import emit,verify_emission
from demo_source import DemoAdapter

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose a fresh output directory')
 a.output.mkdir(parents=True)
 inputs=SourceInputs(ROOT/'examples/demo/source.save.gz',ROOT/'examples/demo/client');adapter=DemoAdapter()
 scene=adapter.export_scene(inputs,a.output/'normalized');source=adapter.verify_source(inputs,scene)
 compile_scene(scene,a.output/'portable',resources=inputs.install);emit(scene,inputs.install,a.output/'minecraft')
 receipt={'passed':True,'source':source,'portable':verify_package(a.output/'portable'),'minecraft':verify_emission(a.output/'minecraft')}
 (a.output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
