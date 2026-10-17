from pathlib import Path
import argparse,sys,tempfile
ROOT=Path(__file__).resolve().parents[1];sys.path[:0]=[str(ROOT/'src'),str(ROOT/'examples')]
from gamesave2minecraft.adapters import SourceInputs
from gamesave2minecraft.minecraft import emit
from demo_source import DemoAdapter

p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
inputs=SourceInputs(ROOT/'examples/demo/source.save.gz',ROOT/'examples/demo/client')
with tempfile.TemporaryDirectory() as tmp:
 scene=DemoAdapter().export_scene(inputs,Path(tmp)/'scene');print(emit(scene,inputs.install,a.output))
