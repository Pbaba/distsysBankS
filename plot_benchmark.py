#!/usr/bin/env python3

import csv
import math
from pathlib import Path

import matplotlib.pyplot as plt


# ---------------------------------------------------------
# Configuration
# ---------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
CSV_FILE = SCRIPT_DIR / "benchmark_results.csv"

THROUGHPUT_OUTPUT = SCRIPT_DIR / "throughput_vs_requests.png"
LATENCY_OUTPUT = SCRIPT_DIR / "latency_vs_requests.png"


# ---------------------------------------------------------
# Load benchmark data
# ---------------------------------------------------------

def load_csv(path):
    if not path.exists():
        raise FileNotFoundError(
            f"Could not find benchmark file:\n{path}"
        )

    rows = []

    with path.open("r", newline="", encoding="utf-8") as file:
        reader = csv.DictReader(file)

        for row in reader:
            rows.append({
                "mode": row["mode"].strip().lower(),
                "requests": float(row["requests"]),
                "throughput_tps": float(row["throughput_tps"]),
                "avg_latency_ms": float(row["avg_latency_ms"]),
            })

    return rows


# ---------------------------------------------------------
# Decide whether logarithmic scale is useful
# ---------------------------------------------------------

def should_use_log_scale(values):
    positive_values = [
        value
        for value in values
        if value > 0 and math.isfinite(value)
    ]

    if len(positive_values) < 2:
        return False

    minimum = min(positive_values)
    maximum = max(positive_values)

    return maximum / minimum >= 10


# ---------------------------------------------------------
# Create a graph
# ---------------------------------------------------------

def create_graph(rows, metric, ylabel, title, output_file):

    sequential = sorted(
        [
            row
            for row in rows
            if row["mode"] == "sequential"
        ],
        key=lambda row: row["requests"]
    )

    concurrent = sorted(
        [
            row
            for row in rows
            if row["mode"] == "concurrent"
        ],
        key=lambda row: row["requests"]
    )

    fig, ax = plt.subplots(figsize=(9.5, 5.8))

    # Sequential
    if sequential:
        ax.plot(
            [row["requests"] for row in sequential],
            [row[metric] for row in sequential],
            marker="o",
            linewidth=2,
            label="Sequential",
        )

    # Concurrent
    if concurrent:
        ax.plot(
            [row["requests"] for row in concurrent],
            [row[metric] for row in concurrent],
            marker="o",
            linewidth=2,
            label="Concurrent",
        )

    # Automatic log scale
    all_values = [row[metric] for row in rows]

    if should_use_log_scale(all_values):
        ax.set_yscale("log")
        scale_text = " (log scale)"
    else:
        scale_text = " (linear scale)"

    ax.set_xlabel("Number of requests")
    ax.set_ylabel(ylabel)
    ax.set_title(title + scale_text)

    ax.grid(True, alpha=0.3)
    ax.legend()

    fig.tight_layout()

    fig.savefig(
        output_file,
        dpi=300,
        bbox_inches="tight"
    )

    plt.close(fig)


# ---------------------------------------------------------
# Main
# ---------------------------------------------------------

def main():

    print("==========================================")
    print("Distributed Bank Benchmark Graph Generator")
    print("==========================================")
    print()

    print(f"Looking for benchmark file:")
    print(f"  {CSV_FILE}")
    print()

    try:
        rows = load_csv(CSV_FILE)
    except Exception as error:
        print("ERROR:")
        print(error)
        return

    if not rows:
        print("ERROR: benchmark_results.csv is empty.")
        return

    print(f"Loaded {len(rows)} benchmark rows.")
    print()

    # Generate throughput graph
    print("Generating throughput graph...")

    create_graph(
        rows=rows,
        metric="throughput_tps",
        ylabel="Throughput (transactions per second)",
        title="Sequential vs Concurrent Throughput",
        output_file=THROUGHPUT_OUTPUT,
    )

    print(f"Created:")
    print(f"  {THROUGHPUT_OUTPUT}")
    print()

    # Generate latency graph
    print("Generating latency graph...")

    create_graph(
        rows=rows,
        metric="avg_latency_ms",
        ylabel="Average latency (ms)",
        title="Sequential vs Concurrent Average Latency",
        output_file=LATENCY_OUTPUT,
    )

    print(f"Created:")
    print(f"  {LATENCY_OUTPUT}")
    print()

    print("==========================================")
    print("DONE")
    print("==========================================")


if __name__ == "__main__":
    main()