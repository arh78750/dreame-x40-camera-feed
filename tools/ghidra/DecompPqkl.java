// DecompPqkl.java — decompile streamer PQKL receive/save path + functions referencing
// the rgb_image/save_pqkl_img/pic_fmt strings. OUT_DIR from GHIDRA_DECOMP_OUT.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import java.io.File;
import java.io.FileWriter;
import java.util.*;

public class DecompPqkl extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv("GHIDRA_DECOMP_OUT");
        if (outDir == null) outDir = "/tmp/ghidra_pqkl";
        new File(outDir).mkdirs();

        String[] wanted = {
            "Pqkl", "PqklImg", "NeedSendPqkl", "TimerPqkl", "PushPqkl", "GetPqkl", "ClearPqkl",
            "FillPqkl", "AvaJsonMsgProcess", "InitializeSubscribe", "ReceiveMsg", "OnPqkl",
            "UploadPhotoToServer", "UploadFileByCurl"
        };
        // explicit addresses of functions that reference the save strings
        long[] addrs = { 0x38dd0L, 0x4bdd4L, 0x4dc1cL, 0x51998L, 0x41530L, 0x37910L, 0x4f7e0L, 0x4f630L };

        DecompInterface dif = new DecompInterface();
        dif.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        Set<Function> toDump = new LinkedHashSet<>();
        for (Function f : fm.getFunctions(true)) {
            String n = f.getName(), pretty = f.getName(true);
            for (String w : wanted) if (n.contains(w) || pretty.contains(w)) { toDump.add(f); break; }
        }
        for (long a : addrs) {
            Address ad = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a);
            Function f = fm.getFunctionContaining(ad);
            if (f != null) toDump.add(f);
        }
        println("Functions selected: " + toDump.size());
        StringBuilder index = new StringBuilder("=== PQKL decomp (streamer) ===\n");
        for (Function f : toDump) {
            try {
                DecompileResults res = dif.decompileFunction(f, 90, monitor);
                if (res == null || !res.decompileCompleted()) { index.append("FAIL  "+f.getEntryPoint()+"  "+f.getName(true)+"\n"); continue; }
                String c = res.getDecompiledFunction().getC();
                String safe = f.getName().replaceAll("[^A-Za-z0-9_]","_"); if (safe.length()>80) safe=safe.substring(0,80);
                String fname = f.getEntryPoint()+"_"+safe+".c";
                FileWriter fw=new FileWriter(new File(outDir,fname));
                fw.write("// "+f.getName(true)+"\n// entry: "+f.getEntryPoint()+"\n\n"+c); fw.close();
                index.append("OK    "+f.getEntryPoint()+"  "+f.getName(true)+"  -> "+fname+"\n");
            } catch (Exception e) { index.append("ERR   "+f.getName(true)+"  "+e.getMessage()+"\n"); }
        }
        FileWriter fw=new FileWriter(new File(outDir,"_index.txt")); fw.write(index.toString()); fw.close();
        println(index.toString());
    }
}
