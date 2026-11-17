package com.java2nb.novel.seo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

/** Bounded streaming XML. Published filenames are immutable; the database is the manifest. */
public class SitemapXmlWriter implements AutoCloseable {
    private final Path temporary;
    private final Path target;
    private final Writer output;
    private final long maxBytes;
    private long bytes;
    private int count;
    private boolean finished;
    public SitemapXmlWriter(Path root,String name,long maxBytes) throws IOException {
        Files.createDirectories(root);
        this.target=root.resolve(name);this.temporary=Files.createTempFile(root,".sitemap-",".tmp");this.maxBytes=maxBytes;
        this.output=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)),StandardCharsets.UTF_8),16384);
        write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    }
    public static String entry(String url,Instant modified) {return "<url><loc>"+escape(url)+"</loc>"+(modified==null?"":"<lastmod>"+modified+"</lastmod>")+"</url>\n";}
    public boolean fits(String entry) {return bytes+entry.getBytes(StandardCharsets.UTF_8).length+10<=maxBytes;}
    public int count(){return count;}
    public void add(String url,Instant modified) throws IOException {String entry=entry(url,modified);if(!fits(entry))throw new IOException("Sitemap part exceeds its uncompressed byte budget");write(entry);count++;}
    private void write(String text)throws IOException {output.write(text);bytes+=text.getBytes(StandardCharsets.UTF_8).length;}
    public void finish()throws IOException {if(finished)return;write("</urlset>\n");output.close();Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);finished=true;}
    @Override public void close()throws IOException {if(!finished){output.close();Files.deleteIfExists(temporary);}}
    public static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
