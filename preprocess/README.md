# preprocess

Turns the daily Warsaw GTFS (`https://436.pl/gtfs/warsaw.zip`, a fork of mkuran's WarsawGTFS) into one compact JSON per line for the app to download.
Run: `python3 build.py --feed /tmp/warsaw.zip --out out [--lines 504] [--gzip]`. Stdlib only. S3 upload + cron: TBD.
