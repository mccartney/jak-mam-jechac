# Public, read-only bucket serving the per-line JSON over HTTPS via the S3 REST
# endpoint: the app GETs https://<bucket>.s3.<region>.amazonaws.com/lines/<line>.json.
resource "aws_s3_bucket" "data" {
  bucket = var.bucket_name
}

# Allow a public bucket policy (but never public ACLs).
resource "aws_s3_bucket_public_access_block" "data" {
  bucket                  = aws_s3_bucket.data.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_policy" "public_read" {
  bucket = aws_s3_bucket.data.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadGetObject"
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.data.arn}/*"
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.data]
}
