# Secret Rotation

Operational procedure for rotating each secret used by gplan-agent. Rotation
is **manual** — we don't auto-rotate to avoid an automation bug locking the
production service out.

After rotation, deploys pull the new value via Secret Manager because the
service is configured with `--set-secrets ...:latest`. No code change ships.

| Secret | Owner of cadence | Default cadence | Exposure if leaked |
|---|---|---|---|
| `anthropic-api-key` | platform | 12 months | Anthropic API spend (no user data) |
| `google-oauth-client` | platform | only on suspicion | Phishing of users via fake login screen |
| `oauth-state-secret` | platform | 6 months | OAuth state-CSRF forgery (limited utility) |
| `scheduler-secret` | platform | 12 months | Cron endpoint trigger (DoS / cost) |
| `admin-secret` | platform | 6 months | Read PII of all users |

Bookmark this file in your password manager so the rotation cadence stays
visible.

---

## anthropic-api-key

```powershell
# 1. Generate a fresh key in the Anthropic console (manual).
$NEW = Read-Host -AsSecureString -Prompt "New Anthropic API key"
$plain = [System.Net.NetworkCredential]::new("", $NEW).Password
$plain | gcloud secrets versions add anthropic-api-key --data-file=-
$plain = $null

# 2. Restart the service so the new env var is picked up. Cloud Run only
#    re-reads secrets on revision boot.
gcloud run services update gplan-agent `
    --region=asia-northeast3 `
    --update-secrets ANTHROPIC_API_KEY=anthropic-api-key:latest

# 3. Verify by tailing /gmail/check-all logs for one cron tick.

# 4. Disable the previous Anthropic key in the Anthropic console once the
#    new one is confirmed working.
```

## oauth-state-secret

```powershell
$NEW = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $NEW, [System.Text.UTF8Encoding]::new($false))
gcloud secrets versions add oauth-state-secret --data-file="$tmp"
Remove-Item $tmp -Force
gcloud run services update gplan-agent `
    --region=asia-northeast3 `
    --update-secrets OAUTH_STATE_SECRET=oauth-state-secret:latest
```

**Side effect**: any in-flight OAuth login (between `/oauth/login` and
`/oauth/callback`) fails with `bad_state` because the new revision can't
verify state issued by the old revision. The user retries the login and it
succeeds. Acceptable.

## scheduler-secret

```powershell
$NEW = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $NEW, [System.Text.UTF8Encoding]::new($false))
gcloud secrets versions add scheduler-secret --data-file="$tmp"
Remove-Item $tmp -Force

# Cloud Run picks up the new value automatically:
gcloud run services update gplan-agent `
    --region=asia-northeast3 `
    --update-secrets SCHEDULER_SECRET=scheduler-secret:latest

# IMPORTANT: the Cloud Scheduler job has the OLD value baked into its
# X-Scheduler-Secret header. Update the job too:
$SECRET = gcloud secrets versions access latest --secret=scheduler-secret
gcloud scheduler jobs update http gmail-check-all `
    --location=asia-northeast3 `
    --update-headers="X-Scheduler-Secret=$SECRET"
```

## admin-secret

```powershell
$NEW = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $NEW, [System.Text.UTF8Encoding]::new($false))
gcloud secrets versions add admin-secret --data-file="$tmp"
Remove-Item $tmp -Force
gcloud run services update gplan-agent `
    --region=asia-northeast3 `
    --update-secrets ADMIN_SECRET=admin-secret:latest

# Save the new value somewhere durable — there's no other place that
# remembers it for ad-hoc curl calls to /admin/*.
gcloud secrets versions access latest --secret=admin-secret
```

## google-oauth-client (Web client_secret)

This is rare — you'd rotate only on a confirmed leak, because every active
session has to re-login afterwards.

1. Console → APIs & Services → Credentials → "GPlan Agent Web" → **RESET SECRET**.
2. Download the updated JSON, save as `credentials_web.json`.
3. Push to Secret Manager:
   ```powershell
   gcloud secrets versions add google-oauth-client --data-file=credentials_web.json
   gcloud run services update gplan-agent `
       --region=asia-northeast3 `
       --update-secrets GOOGLE_OAUTH_CLIENT=google-oauth-client:latest
   ```
4. Tell beta users to log in again (their existing tokens stay valid because
   the API token is independent of the OAuth client_secret, but the next
   refresh fails — which forces re-login).

---

## Cleanup of old secret versions

After rotation and verification, **disable** old versions (don't delete —
keep them for emergency rollback for ~30 days):

```powershell
# List versions
gcloud secrets versions list anthropic-api-key

# Disable old version (use --quiet to skip confirmation if running in script)
gcloud secrets versions disable VERSION_ID --secret=anthropic-api-key
```

After 30 days of stable operation, destroy:

```powershell
gcloud secrets versions destroy VERSION_ID --secret=anthropic-api-key
```

---

## Other credentials worth tracking (not in Secret Manager)

| Item | Where stored | Rotation |
|---|---|---|
| Android release keystore (`release-gplan.jks`) | local `keystore/` + offline backup | only on confirmed compromise (rotation requires every user to uninstall + reinstall) |
| Keystore password | password manager (NOT in `RECOVERY.txt`) | with the keystore |
| Google account login (sooryong.byun@gmail.com) | Google account | per Google's recommendation (use 2FA, hardware key) |
| GitHub PAT (if any) | dev machine | as-needed |
