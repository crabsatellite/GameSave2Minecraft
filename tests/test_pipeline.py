import copy,importlib.util,json,math,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path[:0]=[str(ROOT/'src'),str(ROOT/'examples')]
from demo_source import DemoAdapter
from gamesave2minecraft.adapters import SourceInputs
from gamesave2minecraft.scene import SceneError,validate,relative_file
from gamesave2minecraft.compiler import compile_scene,verify_package
from gamesave2minecraft.transforms import world_matrices,point

class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.inputs=SourceInputs(ROOT/'examples/demo/source.save.gz',ROOT/'examples/demo/client')
        self.adapter=DemoAdapter();self.scene=self.adapter.export_scene(self.inputs,self.root/'normalized')
    def reject(self,change):
        value=copy.deepcopy(self.scene);change(value)
        with self.assertRaises(SceneError):validate(value)
    def test_source_oracle_and_large_inventory_lot(self):
        self.assertTrue(self.adapter.verify_source(self.inputs,self.scene)['passed'])
        compile_scene(self.scene,self.root/'plan',resources=self.inputs.install)
        rows=[r for p in (self.root/'plan/plans').glob('*.json') for r in json.loads(p.read_text())['instances']]
        lot=next(r for r in rows if r['behavior']=='storage')['state']['inventory'][0]
        self.assertEqual((lot['slot'],lot['amount'],lot['source_id']),(37,100000,'demo:ore'))
    def test_hierarchy_rotation_translation_scale(self):
        parent={'id':'parent','position':[10,3,4],'rotation':[0,math.sqrt(.5),0,math.sqrt(.5)],'scale':[2,2,2]}
        child={'id':'child','parent':'parent','position':[1,0,0],'rotation':[0,0,0,1],'scale':[1,1,1]}
        p=point(world_matrices([child,parent])['child'],(0,0,0))
        for actual,want in zip(p,[10,3,2]):self.assertAlmostEqual(actual,want)
    def test_cycles_rejected(self):
        self.reject(lambda s:(s['instances'][0].update(parent='object_chair'),s['instances'][1].update(parent='object_door')))
    def test_missing_dependency_rejected(self):self.reject(lambda s:s['instances'][0].update(asset='missing'))
    def test_unaccounted_source_instance_rejected(self):self.reject(lambda s:s['coverage'].update(expected_instances=16))
    def test_unaccounted_extracted_asset_rejected(self):
        self.reject(lambda s:s['evidence']['asset_inventory']['records'].pop())
        self.reject(lambda s:s['evidence']['asset_inventory']['records'][0].update(status='unresolved'))
        self.reject(lambda s:s['evidence']['asset_inventory']['records'][0].update(status='excluded',reason=''))
        self.reject(lambda s:s['evidence']['asset_inventory']['records'][0].update(targets=['../outside']))
    def test_exclusion_cannot_hide_included_identity(self):
        self.reject(lambda s:s['coverage'].update(expected_instances=16,excluded=[{'source_id':'object_door','reason':'duplicate'}]))
    def test_exclusion_requires_reason(self):self.reject(lambda s:s['coverage'].update(expected_instances=16,excluded=[{'source_id':'invisible'}]))
    def test_duplicate_instance_rejected(self):self.reject(lambda s:s['instances'][1].update(id='object_door'))
    def test_nan_coordinate_rejected(self):self.reject(lambda s:s['instances'][0].update(position=[float('nan'),0,0]))
    def test_zero_quaternion_rejected(self):self.reject(lambda s:s['instances'][0].update(rotation=[0,0,0,0]))
    def test_active_state_cannot_be_an_implicit_string(self):self.reject(lambda s:s['instances'][0]['state'].update(active='false'))
    def test_invalid_growth_timing_rejected(self):self.reject(lambda s:s['instances'][5]['state'].update(remaining_growth_ticks=-1))
    def test_incomplete_growth_appearance_rejected(self):self.reject(lambda s:s['assets'][5].pop('growing_mesh'))
    def test_unknown_behavior_requires_new_mapping(self):self.reject(lambda s:s['assets'][0].update(behavior='unmapped'))
    def test_duplicate_inventory_slot_rejected(self):
        self.reject(lambda s:s['instances'][3]['state']['inventory'].append(copy.deepcopy(s['instances'][3]['state']['inventory'][0])))
    def test_inventory_capacity_rejected(self):self.reject(lambda s:s['instances'][3]['state'].update(inventory_slots=10))
    def test_path_escape_rejected(self):
        for path in ['../private.bin','/outside','C:/outside','textures\\outside']:
            with self.subTest(path=path),self.assertRaises(SceneError):relative_file(path,self.root)
    def test_invalid_mesh_topology_rejected(self):
        # An actual resource file with an out-of-range index must fail before emission.
        import shutil
        resources=self.root/'resources';shutil.copytree(self.inputs.install,resources)
        p=resources/'meshes/door.json';m=json.loads(p.read_text());m['groups'][0]['indices'][0]=999999;p.write_text(json.dumps(m))
        with self.assertRaises(SceneError):compile_scene(self.scene,self.root/'bad',resources=resources)
        self.assertFalse((self.root/'bad').exists())
    def test_deterministic_bytes_and_fresh_output(self):
        a=compile_scene(self.scene,self.root/'a',resources=self.inputs.install,chunk_rows=2)
        b=compile_scene(self.scene,self.root/'b',resources=self.inputs.install,chunk_rows=2)
        self.assertEqual(a,b);self.assertEqual(verify_package(self.root/'a')['instances'],15)
        with self.assertRaises(SceneError):compile_scene(self.scene,self.root/'a')
    def test_readback_detects_tampering_and_extra_files(self):
        compile_scene(self.scene,self.root/'a');p=self.root/'a/plans/part_00000.json';p.write_text('{}')
        with self.assertRaises(SceneError):verify_package(self.root/'a')
        compile_scene(self.scene,self.root/'b');(self.root/'b/private.txt').write_text('unexpected')
        with self.assertRaises(SceneError):verify_package(self.root/'b')
    def test_partition_retains_all_instances(self):
        s=copy.deepcopy(self.scene);base=s['instances'][0]
        s['instances']=[{**base,'id':f'object_{i}','position':[i*2,0,0]} for i in range(513)];s['coverage']['expected_instances']=513
        compile_scene(s,self.root/'large',chunk_rows=128,maximum_file_bytes=4000)
        self.assertEqual(verify_package(self.root/'large')['instances'],513)
        self.assertTrue(all(p.stat().st_size<=4000 for p in (self.root/'large').rglob('*.json') if p.name!='manifest.json'))

if __name__=='__main__':unittest.main()
