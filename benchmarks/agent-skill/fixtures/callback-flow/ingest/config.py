from ingest.validators import strict_external_id as partner_validator
from ingest.validators import strict_external_id as admin_validator


def partner_pipeline():
    return partner_validator


def admin_pipeline():
    return admin_validator
