# preprocess

Turns the daily zbiorkom.live Warsaw GTFS (`https://cdn.zbiorkom.live/gtfs/warsaw.zip`) into one compact JSON per line for the app to download.
Run: `python3 build.py --feed /tmp/warsaw.zip --out out [--lines 504] [--gzip]`. Stdlib only. S3 upload + cron: TBD.
