# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Grzegorz Olędzki

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state in a private, versioned bucket (created out-of-band so that
  # `tofu destroy` can never delete the bucket holding its own state).
  # use_lockfile = native S3 state locking, no DynamoDB needed (OpenTofu >= 1.10).
  backend "s3" {
    bucket       = "jak-mam-jechac-tfstate"
    key          = "infra/terraform.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
}
