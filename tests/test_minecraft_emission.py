import json,math,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path[:0]=[str(ROOT/'src'),str(ROOT/'examples')]
from gamesave2minecraft.minecraft import emit,decompose
from gamesave2minecraft.transforms import trs
from gamesave2minecraft.scene import SceneError
from gamesave2minecraft.adapters import SourceInputs
from demo_source import DemoAdapter

class EmissionTests(unittest.TestCase):
 def test_nonuniform_transform_roundtrip(self):
  for angle in [0,.2,math.pi/2,math.pi,3.9]:
   m=trs([12,-4,.5],[0,math.sin(angle/2),0,math.cos(angle/2)],[2,.5,3]);p,q,s=decompose(m)
   for a,b in zip(m,trs(p,q,s)):self.assertAlmostEqual(a,b)
 def test_shear_and_reflection_are_visible_target_requirements(self):
  for m in [(1,.5,0,0,0,1,0,0,0,0,1,0,0,0,0,1),trs([0,0,0],[0,0,0,1],[-1,1,1])]:
   with self.assertRaises(SceneError):decompose(m)
 def test_real_generated_metadata_and_pack(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);inputs=SourceInputs(ROOT/'examples/demo/source.save.gz',ROOT/'examples/demo/client')
   scene=DemoAdapter().export_scene(inputs,root/'scene');receipt=emit(scene,inputs.install,root/'runtime')
   self.assertEqual(receipt['instances'],15)
   index=json.loads((root/'runtime/generated/data/gamesave2mc/scenes/original_demo/index.json').read_text())
   rows=json.loads((root/'runtime/generated/data/gamesave2mc/scenes/original_demo/part_00000.json').read_text())['placements']
   self.assertEqual(len(index['floorCandidates']),8)
   lot=next(x for x in rows if x['asset']=='chest')['inventory'][0]
   self.assertEqual(lot['source_id'],'demo:ore');self.assertEqual(lot['amount'],100000)
   self.assertTrue((root/'runtime/resource-pack/assets/gamesave2mc/meshes/crop_growing.json.gz').is_file())
   scene['assets'][0]['id']='new_game_door'
   scene['instances'][0]['asset']='new_game_door'
   emit(scene,inputs.install,root/'other-game')
   carrier=root/'other-game/generated/assets/gamesave2mc/models/item/new_game_door.json'
   self.assertEqual(json.loads(carrier.read_text())['parent'],'minecraft:builtin/entity')

if __name__=='__main__':unittest.main()
