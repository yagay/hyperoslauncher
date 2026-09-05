package com.yagay.desktopgridx;

import org.tukaani.xz.XZInputStream;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class GnuDebugDataResolver {
    static final long EXPECTED_XY_DISTANCE = 0xACL;

    static final class Result {
        boolean foundDebugData;
        boolean symbolsResolved;
        boolean structuralVerified;
        boolean success;
        long x, y, hotseat, preferenceGetInt, preferencePutInt, getScreenGrid;
        long iconSizeProviderQualified, iconSizeProviderCall, computeCellWidth;
        String sha256="";
        String error="";
        String report="";
    }

    private GnuDebugDataResolver() {}

    static Result resolve(File soFile) {
        Result r = new Result();
        StringBuilder out = new StringBuilder();
        try {
            r.sha256 = sha256(soFile);
            byte[] elf = readAll(new FileInputStream(soFile), 256L * 1024L * 1024L);
            ElfImage outer = new ElfImage(elf);
            byte[] debug = outer.sectionBytes(".gnu_debugdata");
            if (debug == null) {
                out.append("gnu_debugdata=NOT_FOUND\n");
                r.report = out.toString();
                return r;
            }
            r.foundDebugData = true;
            out.append("gnu_debugdata=FOUND\ncompressed_size=").append(debug.length).append('\n');
            byte[] mini;
            try (XZInputStream xz = new XZInputStream(new ByteArrayInputStream(debug))) {
                mini = readAll(xz, 128L * 1024L * 1024L);
            }
            out.append("mini_elf_size=").append(mini.length).append('\n');
            ElfImage m = new ElfImage(mini);
            Map<String,Long> matches = m.findSymbols(Arrays.asList(
                    "DeviceConfigs16get_cell_count_x",
                    "DeviceConfigs16get_cell_count_y",
                    "DeviceConfigs21get_hotseat_max_count",
                    "PreferenceUtils7get_int",
                    "PreferenceUtils7put_int",
                    "settings_handler15get_screen_grid",
                    "IconSizeProvider22is_parameter_qualified",
                    "DeviceConfigs36compute_cell_width_px_by_orientation"));
            for (Map.Entry<String,Long> e : matches.entrySet()) {
                out.append("symbol[").append(e.getKey()).append("]=0x").append(Long.toHexString(e.getValue())).append('\n');
            }

            r.x = findUniqueEnding(matches, "DeviceConfigs16get_cell_count_x");
            r.y = findUniqueEnding(matches, "DeviceConfigs16get_cell_count_y");
            r.hotseat = findUniqueEnding(matches, "DeviceConfigs21get_hotseat_max_count");
            r.preferenceGetInt = findUniqueEnding(matches, "PreferenceUtils7get_int");
            r.preferencePutInt = findUniqueEnding(matches, "PreferenceUtils7put_int");
            r.getScreenGrid = findUniqueEnding(matches, "settings_handler15get_screen_grid");
            r.iconSizeProviderQualified = findUniqueContaining(matches, "IconSizeProvider22is_parameter_qualified");
            r.computeCellWidth = findUniqueEnding(matches, "DeviceConfigs36compute_cell_width_px_by_orientation");

            r.symbolsResolved = r.x > 0 && r.y > 0 && r.hotseat > 0;
            r.structuralVerified = r.symbolsResolved && r.y > r.x && (r.y - r.x) == EXPECTED_XY_DISTANCE;
            r.success = r.structuralVerified;

            out.append("resolved_x=0x").append(Long.toHexString(r.x)).append('\n')
                    .append("resolved_y=0x").append(Long.toHexString(r.y)).append('\n')
                    .append("resolved_hotseat=0x").append(Long.toHexString(r.hotseat)).append('\n')
                    .append("resolved_preference_get_int=0x").append(Long.toHexString(r.preferenceGetInt)).append('\n')
                    .append("resolved_preference_put_int=0x").append(Long.toHexString(r.preferencePutInt)).append('\n')
                    .append("resolved_get_screen_grid=0x").append(Long.toHexString(r.getScreenGrid)).append('\n')
                    .append("resolved_icon_size_provider_qualified=0x").append(Long.toHexString(r.iconSizeProviderQualified)).append('\n')
                    .append("resolved_compute_cell_width=0x").append(Long.toHexString(r.computeCellWidth)).append('\n')
                    .append("xy_distance=0x").append(Long.toHexString(r.y-r.x)).append('\n')
                    .append("symbols_resolved=").append(r.symbolsResolved).append('\n')
                    .append("structural_verified=").append(r.structuralVerified).append('\n')
                    .append("success=").append(r.success).append('\n');
        } catch (Throwable t) {
            r.error = t.toString();
            out.append("error=").append(stack(t)).append('\n');
        }
        r.report = out.toString();
        return r;
    }

    static File extractLauncherSo(File apk, File outFile) throws IOException {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry ze = zf.getEntry("lib/arm64-v8a/libapp_launcher.so");
            if (ze == null) return null;
            try (InputStream in = zf.getInputStream(ze); OutputStream out = new FileOutputStream(outFile)) {
                byte[] b = new byte[1024*1024]; int n;
                while ((n=in.read(b))>0) out.write(b,0,n);
            }
            return outFile;
        }
    }

    private static long findUniqueEnding(Map<String,Long> m, String suffix) {
        long v = 0; int count = 0;
        for (Map.Entry<String,Long> e : m.entrySet()) {
            if (e.getKey().endsWith(suffix)) { v=e.getValue(); count++; }
        }
        return count == 1 ? v : 0;
    }

    private static long findUniqueContaining(Map<String,Long> m, String token) {
        long v = 0; int count = 0;
        for (Map.Entry<String,Long> e : m.entrySet()) {
            if (e.getKey().contains(token)) { v=e.getValue(); count++; }
        }
        return count == 1 ? v : 0;
    }

    private static final class Section { String name; long off,size,entsize; int type,link; }

    private static final class ElfImage {
        final byte[] b;
        final ByteBuffer bb;
        final List<Section> sections = new ArrayList<>();
        ElfImage(byte[] b) throws IOException {
            this.b=b;
            if (b.length < 64 || b[0]!=0x7f || b[1]!='E' || b[2]!='L' || b[3]!='F') throw new IOException("not ELF");
            if (b[4] != 2 || b[5] != 1) throw new IOException("only ELF64 little-endian supported");
            bb=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            long shoff = bb.getLong(0x28);
            int shentsize = u16(0x3a), shnum = u16(0x3c), shstrndx = u16(0x3e);
            if (shnum <= 0 || shentsize < 64 || shoff < 0 || shoff + (long)shentsize*shnum > b.length) throw new IOException("bad section table");
            int[] names = new int[shnum];
            long[] offs = new long[shnum], sizes = new long[shnum], ents = new long[shnum];
            int[] types = new int[shnum], links = new int[shnum];
            for (int i=0;i<shnum;i++) {
                int p=(int)(shoff+(long)i*shentsize);
                names[i]=bb.getInt(p); types[i]=bb.getInt(p+4); offs[i]=bb.getLong(p+24); sizes[i]=bb.getLong(p+32); links[i]=bb.getInt(p+40); ents[i]=bb.getLong(p+56);
                if (types[i] != 8) range(offs[i],sizes[i]);
            }
            if (shstrndx<0 || shstrndx>=shnum) throw new IOException("bad shstrndx");
            if (types[shstrndx] == 8) throw new IOException("shstrtab cannot be NOBITS");
            byte[] shstr = Arrays.copyOfRange(b,(int)offs[shstrndx],(int)(offs[shstrndx]+sizes[shstrndx]));
            for (int i=0;i<shnum;i++) {
                Section s=new Section(); s.name=cstr(shstr,names[i]); s.off=offs[i]; s.size=sizes[i]; s.entsize=ents[i]; s.type=types[i]; s.link=links[i]; sections.add(s);
            }
        }
        byte[] sectionBytes(String name) {
            for (Section s:sections) if (name.equals(s.name) && s.type != 8) return Arrays.copyOfRange(b,(int)s.off,(int)(s.off+s.size));
            return null;
        }
        Map<String,Long> findSymbols(List<String> needles) throws IOException {
            LinkedHashMap<String,Long> out=new LinkedHashMap<>();
            for (Section sym:sections) {
                if (sym.type!=2 || sym.entsize<24 || sym.link<0 || sym.link>=sections.size()) continue;
                Section str=sections.get(sym.link);
                if (str.type==8) continue;
                range(str.off,str.size); range(sym.off,sym.size);
                byte[] st=Arrays.copyOfRange(b,(int)str.off,(int)(str.off+str.size));
                long count=sym.size/sym.entsize;
                for (long i=0;i<count;i++) {
                    long pp=sym.off+i*sym.entsize;
                    if (pp<0 || pp+24>b.length) break;
                    int p=(int)pp;
                    int nameOff=bb.getInt(p);
                    long value=bb.getLong(p+8);
                    if (nameOff<=0 || nameOff>=st.length || value==0) continue;
                    String name=cstr(st,nameOff);
                    for (String n:needles) if (name.contains(n)) { out.put(name,value); break; }
                }
            }
            return out;
        }
        int u16(int p){ return bb.getShort(p)&0xffff; }
        void range(long off,long len) throws IOException { if(off<0||len<0||off+len<off||off+len>b.length) throw new IOException("section out of range"); }
    }

    private static String cstr(byte[] b,int p) {
        if (p<0 || p>=b.length) return "";
        int e=p; while(e<b.length && b[e]!=0)e++;
        return new String(b,p,e-p,StandardCharsets.UTF_8);
    }
    private static byte[] readAll(InputStream in,long limit) throws IOException {
        try (InputStream src=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[1024*1024]; int n; long total=0;
            while((n=src.read(b))>0){ total+=n; if(total>limit)throw new IOException("input too large"); out.write(b,0,n); }
            return out.toByteArray();
        }
    }
    private static String sha256(File f) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);} StringBuilder s=new StringBuilder(); for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x)); return s.toString();
    }
    private static String stack(Throwable t){StringWriter sw=new StringWriter();t.printStackTrace(new PrintWriter(sw));return sw.toString();}
}
