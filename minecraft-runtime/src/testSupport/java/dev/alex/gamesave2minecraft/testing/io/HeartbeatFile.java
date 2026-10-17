package dev.alex.gamesave2minecraft.testing.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Test-only atomic heartbeat publication; a bounded Windows sharing retry cannot invent progress. */
public final class HeartbeatFile {
    public static void write(Path path,String value)throws IOException{
        Files.createDirectories(path.getParent());
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temporary,value,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        for(int attempt=0;;attempt++){
            try{
                try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(AccessDeniedException conflict){
                if(attempt>=8)throw conflict;
                try{Thread.sleep(25);}
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    var failure=new InterruptedIOException("Heartbeat publication interrupted");
                    failure.initCause(interrupted);throw failure;
                }
            }
        }
    }
    private HeartbeatFile(){}
}
