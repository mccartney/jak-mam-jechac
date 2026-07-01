# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Grzegorz Olędzki

# GitHub Actions deploys via OIDC — no stored AWS keys. The CI job presents a
# short-lived OIDC token and assumes this role for ~1 h temporary credentials.

locals {
  oidc_host = "token.actions.githubusercontent.com"
}

# Account-wide trust anchor for GitHub's OIDC. thumbprint_list is omitted on
# purpose: AWS validates GitHub's cert against its own trust store and populates
# it automatically (provider >= 5.x).
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "deploy" {
  name = "jak-mam-jechac-ci-deploy"

  # StringEquals (exact, no wildcards) on the full sub is what keeps forks and
  # any other repo/branch/PR out: only main of this exact repo can assume it.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
          "${local.oidc_host}:sub" = "repo:${var.github_repo}:ref:refs/heads/${var.github_branch}"
        }
      }
    }]
  })
}

# Least privilege: write objects to this bucket and list it (for `aws s3 sync`).
resource "aws_iam_role_policy" "deploy_s3" {
  name = "s3-write"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.data.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.data.arn
      }
    ]
  })
}
