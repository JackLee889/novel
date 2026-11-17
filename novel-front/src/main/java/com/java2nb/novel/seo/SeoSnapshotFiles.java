package com.java2nb.novel.seo;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

/** POSIX/NFS file lock shared by every snapshot publisher and collector. */
public final class SeoSnapshotFiles {
    public static final String LOCK_FILE=".seo-snapshots.lock";
    private static final ReentrantLock LOCAL=new ReentrantLock(true);
    private SeoSnapshotFiles(){}
    public static Path root(Path directory)throws IOException {
        if(Files.isSymbolicLink(directory))throw new IOException("Snapshot directory must not be a symlink");
        Files.createDirectories(directory);return directory.toRealPath();
    }
    public static Guard lock(Path directory)throws IOException,InterruptedException {
        LOCAL.lockInterruptibly();return open(directory,false);
    }
    public static Guard tryLock(Path directory)throws IOException {
        if(!LOCAL.tryLock())return null;return open(directory,true);
    }
    private static Guard open(Path directory,boolean nonblocking)throws IOException {
        FileChannel channel=null;
        try {
            Path root=root(directory);
            channel=FileChannel.open(root.resolve(LOCK_FILE),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            FileLock lock=nonblocking?channel.tryLock():channel.lock();
            if(lock==null){channel.close();LOCAL.unlock();return null;}
            return new Guard(channel,lock);
        }catch(IOException|RuntimeException e){if(channel!=null)try{channel.close();}catch(IOException suppressed){e.addSuppressed(suppressed);}LOCAL.unlock();throw e;}
    }
    public static final class Guard implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;private boolean closed;
        private Guard(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
        public void close()throws IOException {if(closed)return;closed=true;try{try{lock.release();}finally{channel.close();}}finally{LOCAL.unlock();}}
    }
    public static String hash(Path file)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    /** Reuse immutable content only after verifying its bytes; refresh retirement grace. */
    public static void reuse(Path file,String expectedHash)throws Exception {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!hash(file).equals(expectedHash))throw new IOException("Immutable snapshot checksum mismatch");
        Files.setLastModifiedTime(file,FileTime.from(Instant.now()));
    }
    public static void immutableIndex(Path root,String name,byte[] body,String expectedHash)throws Exception {
        Path target=root.resolve(name);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){reuse(target,expectedHash);return;}
        Path temporary=Files.createTempFile(root,".index-",".tmp");
        try{Files.write(temporary,body);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(temporary);}
    }
}
