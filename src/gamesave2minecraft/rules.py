"""Capability contracts shared by source adapters and the Minecraft runtime."""
RULES = {
 'door': {'required':['hinge','open_degrees'],'states':['active'],'observations':['open/close','swing obstruction','collision update','save/reload','server synchronization']},
 'seat': {'required':['socket'],'states':['occupant'],'observations':['sit','reject double occupancy','dismount','remove occupied seat','server synchronization']},
 'bed': {'required':['socket'],'states':['occupant'],'observations':['native sleep rules','occupancy','wake','dimension policy','server synchronization']},
 'storage': {'required':[],'states':['inventory_slots','inventory'],'observations':['slot identity','amount preservation','menu pagination','move with contents','save/reload']},
 'plant': {'required':['mesh','growing_mesh','grow_ticks'],'states':['active','remaining_growth_ticks'],'observations':['visible stages','harvest','yield mapping','regrowth','save/reload']},
 'light': {'required':[],'states':['active'],'observations':['toggle','appearance/light update','save/reload','server synchronization']},
 'workstation': {'required':[],'states':['inventory_slots','inventory'],'observations':['declared target recipe policy','storage preservation','menu validity','save/reload']},
 'static': {'required':[],'states':[],'observations':['appearance','collision','selection','placement/removal','save/reload']},
}

def acceptance_matrix(scene):
    observed={a['behavior'] for a in scene['assets']}
    return {name:RULES[name] for name in sorted(observed)}
