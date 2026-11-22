"""Charts for the technical report from benchmark CSVs.
Usage: python plot_results.py [results_dir]
Inputs (any that exist): runner_bench.csv, saarthi_perf.csv (exported from the app), app_memory.csv
Outputs: PNGs next to the CSVs.
"""
import csv, os, statistics, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

D = sys.argv[1] if len(sys.argv) > 1 else "results"
TPS_TARGET, RAM_TARGET = 22, 1800

def rows(name):
    p = os.path.join(D, name)
    return list(csv.DictReader(open(p))) if os.path.exists(p) else []

def bar(values, labels, title, ylabel, target, fname, higher_is_better=True):
    plt.figure(figsize=(6, 3.4))
    colors = ["#059669" if (v >= target if higher_is_better else v <= target) else "#E11D48" for v in values]
    plt.bar(labels, values, color=colors)
    plt.axhline(target, color="black", linestyle="--", linewidth=1, label=f"target {target}")
    plt.title(title); plt.ylabel(ylabel); plt.legend(); plt.tight_layout()
    plt.savefig(os.path.join(D, fname), dpi=200); plt.close()
    print("wrote", fname, "median", statistics.median(values))

for name, key_tps, key_ram in (("runner_bench.csv", "decode_tps", "peak_rss_mb"), ("saarthi_perf.csv", "decode_tps", "peak_rss_mb")):
    r = rows(name)
    if not r: continue
    tag = name.split(".")[0]
    labels = [str(i + 1) for i in range(len(r))]
    bar([float(x[key_tps]) for x in r], labels, f"Decode speed ({tag})", "tokens / s", TPS_TARGET, f"{tag}_tps.png")
    bar([float(x[key_ram]) for x in r], labels, f"Peak RAM ({tag})", "MB", RAM_TARGET, f"{tag}_ram.png", higher_is_better=False)

m = rows("app_memory.csv")
if m:
    plt.figure(figsize=(7, 3.4))
    t = [int(x["t_s"]) for x in m]
    plt.plot(t, [int(x["total_pss_mb"]) for x in m], label="PSS")
    plt.plot(t, [int(x["total_rss_mb"]) for x in m], label="RSS")
    plt.axhline(RAM_TARGET, color="#E11D48", linestyle="--", linewidth=1, label="1.8 GB budget")
    plt.xlabel("seconds"); plt.ylabel("MB"); plt.title("Saarthi memory over a session (cascading load/unload)")
    plt.legend(); plt.tight_layout(); plt.savefig(os.path.join(D, "app_memory.png"), dpi=200); plt.close()
    print("wrote app_memory.png")
