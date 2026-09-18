import argparse

from .audit import main as audit_main
from .debug_projection import main as debug_projection_main
from .pdf_schedule import main as pdf_main


parser = argparse.ArgumentParser(description="BART source-data audit")
sub = parser.add_subparsers(dest="command", required=True)
sub.add_parser("audit", help="run the raw fixture audit")
sub.add_parser("pdf-compare", help="extract official Yellow-line PDFs and compare GTFS-RT")
sub.add_parser("debug-projection", help="diagnose ETD/GTFS-RT/projection discrepancies")
args, remainder = parser.parse_known_args()

if args.command == "audit":
    raise SystemExit(audit_main(["audit", *remainder]))
if args.command == "debug-projection":
    raise SystemExit(debug_projection_main(remainder))
raise SystemExit(pdf_main(remainder))
