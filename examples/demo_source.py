"""Original miniature source-game format. No proprietary game inputs are used."""
from __future__ import annotations
import gzip,hashlib,json
from pathlib import Path
from gamesave2minecraft.adapters import SourceInputs
from gamesave2minecraft.scene import canonical,require,validate

class DemoAdapter:
    @staticmethod
    def asset_inventory(install: Path) -> dict:
        names=sorted(p.relative_to(install).as_posix() for folder in ['meshes','textures'] for p in (install/folder).rglob('*') if p.is_file())
        return {'expected':len(names),'records':[{'source_id':name,'status':'mapped','targets':[name]} for name in names]}

    def probe(self, inputs: SourceInputs) -> dict:
        require(inputs.install is not None,'Demo client directory required')
        version=json.loads((inputs.install/'version.json').read_text(encoding='utf-8'))
        require(version=={'engine':'original-fixture','version':'1'},'Unsupported demo client version')
        return {'adapter':'original-demo/1','save_sha256':hashlib.sha256(inputs.save.read_bytes()).hexdigest(),'client_version':version}

    def export_scene(self, inputs: SourceInputs, output: Path) -> dict:
        evidence=self.probe(inputs)
        evidence['asset_inventory']=self.asset_inventory(inputs.install)
        save=json.loads(gzip.decompress(inputs.save.read_bytes()))
        require(save.get('format')=='original-demo-save/1','Unsupported demo save')
        catalog=json.loads((inputs.install/'catalog.json').read_text(encoding='utf-8'))
        scene={'schema':'gamesave2minecraft.scene/1','id':'original_demo',
               'coordinates':{'unit_metres':1,'up':'y','handedness':'right'},
               'assets':catalog['assets'],'instances':save['placed_objects'],
               'coverage':{'expected_instances':len(save['placed_objects']),'excluded':[]},
               'evidence':evidence}
        validate(scene,resources=inputs.install)
        require(not output.exists(),'Adapter output must be fresh')
        output.mkdir(parents=True);(output/'scene.json').write_bytes(canonical(scene))
        return scene

    def verify_source(self, inputs: SourceInputs, scene: dict) -> dict:
        saved=json.loads(gzip.decompress(inputs.save.read_bytes()))['placed_objects']
        by_id={row['id']:row for row in saved}
        require(len(by_id)==len(saved)==len(scene['instances']),'Demo source identity coverage mismatch')
        require(all(by_id.get(row['id'])==row for row in scene['instances']),'Demo source layout/state differs')
        require(scene['evidence']['asset_inventory']==self.asset_inventory(inputs.install),'Demo extracted asset inventory differs')
        return {'passed':True,'instances':len(saved),'extracted_assets':scene['evidence']['asset_inventory']['expected'],'scope':'Exact saved data of the original procedural fixture, not a commercial-game experiment'}
