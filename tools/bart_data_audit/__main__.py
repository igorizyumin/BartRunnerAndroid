import argparse

from .audit import main as audit_main
from .pdf_schedule import main as pdf_main


parser = argparse.ArgumentParser(description="BART source-data audit")
sub = parser.add_subparsers(dest="command", required=True)
sub.add_parser("audit", help="run the raw fixture audit")
sub.add_parser("pdf-compare", help="extract official Yellow-line PDFs and compare GTFS-RT")
args, remainder = parser.parse_known_args()

if args.command == "audit":
    raise SystemExit(audit_main(["audit", *remainder]))
raise SystemExit(pdf_main(remainder))
