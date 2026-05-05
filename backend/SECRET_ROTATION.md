# Secret Rotation

The stateless parser-only backend has exactly one secret:

| Secret | Default cadence | Exposure if leaked |
|---|---|---|
| `anthropic-api-key` | 12 months | Anthropic API spend (no user data) |

Rotation is **manual** — we don't auto-rotate to avoid an automation bug
locking the production service out. After rotation, deploys pull the new
value via Secret Manager because the service is configured with
`--set-secrets ANTHROPIC_API_KEY=anthropic-api-key:latest`. No code change ships.

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

# 3. Verify by tailing /parse logs from the device:
#    gcloud run services logs read gplan-agent --region=asia-northeast3 --limit=50

# 4. Disable the previous Anthropic key in the Anthropic console once the
#    new one is confirmed working.
```

## Cleanup of old secret versions

After rotation and verification, **disable** old versions (don't delete —
keep them for emergency rollback for ~30 days):

```powershell
gcloud secrets versions list anthropic-api-key
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
| Google Web OAuth client ID (`GOOGLE_WEB_CLIENT_ID` env var) | not sensitive — embedded in the APK; just the audience claim for ID tokens | only if you rotate the OAuth client |
| Android release keystore (`release-gplan.jks`) | local `keystore/` + offline backup | only on confirmed compromise (rotation requires every user to uninstall + reinstall) |
| Keystore password | password manager (NOT in `RECOVERY.txt`) | with the keystore |
| Google account login | Google account | per Google's recommendation (use 2FA, hardware key) |
