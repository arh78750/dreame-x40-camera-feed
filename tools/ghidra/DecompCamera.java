// DecompCamera.java — Ghidra headless post-script.
// Decompiles a target set of functions (by symbol substring) plus anything that
// calls cv::imencode / imwrite / fopen / nn_send / curl, and writes C to OUT_DIR.
//
// Run via analyzeHeadless with -postScript DecompCamera.java
// OUT_DIR is taken from the GHIDRA_DECOMP_OUT env var (set by the wrapper).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.address.Address;
import java.io.File;
import java.io.FileWriter;
import java.util.*;

public class DecompCamera extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv("GHIDRA_DECOMP_OUT");
        if (outDir == null) outDir = "/tmp/ghidra_decomp";
        new File(outDir).mkdirs();

        // Substrings of (demangled or mangled) names we want to dump.
        String[] wanted = {
            "imencode", "imwrite", "imread",         // OpenCV encode/decode
            "PostJsonByCurl", "UploadFileByCurl",    // cloud upload
            "UploadPhotoToServer", "sendMsg2BigData",
            "CameraThread", "SaveImageFromCache",
            "GetImageFromCache", "SendMsg2VideoMonitor",
            "ProcInternalMsg", "HandleVMStatusChg",
            "ReciveMsgFromVideoMonitor", "VideoMonitorInit",
            "CheckHeartBeatWithVM", "timer_SendMsgToCVT",
            "getOnePhotoData", "setOnePhotoData",
            "AvaJsonMsgProcess", "OpenCamera", "CloseCamera",
            "SaveImage", "save_image", "fopen", "fwrite", "imgcodecs"
        };

        DecompInterface dif = new DecompInterface();
        dif.openProgram(currentProgram);

        FunctionManager fm = currentProgram.getFunctionManager();
        Set<Function> toDump = new LinkedHashSet<>();

        for (Function f : fm.getFunctions(true)) {
            String n = f.getName();
            String pretty = f.getName(true); // namespace-qualified
            for (String w : wanted) {
                if (n.contains(w) || pretty.contains(w)) { toDump.add(f); break; }
            }
        }

        println("Functions selected for decompilation: " + toDump.size());

        StringBuilder index = new StringBuilder();
        index.append("=== Decompiled functions ===\n");

        for (Function f : toDump) {
            try {
                DecompileResults res = dif.decompileFunction(f, 60, monitor);
                if (res == null || !res.decompileCompleted()) {
                    index.append("FAIL  " + f.getEntryPoint() + "  " + f.getName(true) + "\n");
                    continue;
                }
                String c = res.getDecompiledFunction().getC();
                String safe = f.getName().replaceAll("[^A-Za-z0-9_]", "_");
                if (safe.length() > 80) safe = safe.substring(0, 80);
                String fname = f.getEntryPoint().toString() + "_" + safe + ".c";
                File out = new File(outDir, fname);
                FileWriter fw = new FileWriter(out);
                fw.write("// " + f.getName(true) + "\n");
                fw.write("// entry: " + f.getEntryPoint() + "\n\n");
                fw.write(c);
                fw.close();
                index.append("OK    " + f.getEntryPoint() + "  " + f.getName(true) + "  -> " + fname + "\n");
            } catch (Exception e) {
                index.append("ERR   " + f.getName(true) + "  " + e.getMessage() + "\n");
            }
        }

        FileWriter fw = new FileWriter(new File(outDir, "_index.txt"));
        fw.write(index.toString());
        fw.close();
        println("Wrote decomp to " + outDir);
        println(index.toString());
    }
}
