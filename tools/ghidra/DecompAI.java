// DecompAI.java — Ghidra headless post-script for node_camera_ai.so.
// Decompiles the RGB per-frame path + data-collection write path so we can find
// a clean injection point for "imwrite every inference frame".
// OUT_DIR from GHIDRA_DECOMP_OUT env var.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import java.io.File;
import java.io.FileWriter;
import java.util.*;

public class DecompAI extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outDir = System.getenv("GHIDRA_DECOMP_OUT");
        if (outDir == null) outDir = "/tmp/ghidra_decomp_ai";
        new File(outDir).mkdirs();

        String[] wanted = {
            "ProcessImage", "ProcessIrImage", "ConvertIntoAIImageMsg",
            "ConvertIntoAIFloatImageMsg", "Dispatch", "PerformAction",
            "Inference", "RegisterInferenceJobs", "RegisterModel",
            "SaveImageAndReturnPath", "WriteImage", "CollectDataAndReturnAbsolutePath",
            "SetCollectionDir", "SetQuota", "DetectionDataCollectionJob",
            "InitializeSubscribe", "InitializePublish",
            "Publish", "CreateSyncData", "ConvertSyncDataIntoString",
            "FuseImageWithObjects", "FuseImageWithSegMap",
            "ProcessCmd", "ProcessAIFuseCmd", "ProcessMissionConfig",
            "imencode", "imwrite", "cvtColor"
        };

        DecompInterface dif = new DecompInterface();
        dif.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        Set<Function> toDump = new LinkedHashSet<>();
        for (Function f : fm.getFunctions(true)) {
            String n = f.getName();
            String pretty = f.getName(true);
            for (String w : wanted) {
                if (n.contains(w) || pretty.contains(w)) { toDump.add(f); break; }
            }
        }
        println("Functions selected: " + toDump.size());

        StringBuilder index = new StringBuilder("=== Decompiled (node_camera_ai.so) ===\n");
        for (Function f : toDump) {
            try {
                DecompileResults res = dif.decompileFunction(f, 90, monitor);
                if (res == null || !res.decompileCompleted()) {
                    index.append("FAIL  " + f.getEntryPoint() + "  " + f.getName(true) + "\n");
                    continue;
                }
                String c = res.getDecompiledFunction().getC();
                String safe = f.getName().replaceAll("[^A-Za-z0-9_]", "_");
                if (safe.length() > 80) safe = safe.substring(0, 80);
                String fname = f.getEntryPoint().toString() + "_" + safe + ".c";
                FileWriter fw = new FileWriter(new File(outDir, fname));
                fw.write("// " + f.getName(true) + "\n// entry: " + f.getEntryPoint() + "\n\n");
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
