package dev.alex.gamesave2minecraft;

import net.minecraft.world.phys.Vec3;

/** One source panel per hinge. The same function drives collision and rendering. */
public final class DoorMotion {
    public static Vec3 point(ModelCatalog.Door door,Vec3 p,boolean right){
        double hinge=right?door.right():door.left();
        double x=p.x-hinge,z=p.z-door.z();
        return right?new Vec3(hinge-z,p.y,door.z()+x):new Vec3(hinge+z,p.y,door.z()-x);
    }
    public static boolean right(ModelCatalog.Door door,double x){return door.mode().equals("double")&&x>=(door.left()+door.right())*.5;}
    private DoorMotion(){}
}
