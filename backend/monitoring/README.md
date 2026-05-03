# Monitoring

Alert policies for the gplan-agent Cloud Run backend, kept as JSON so they
can be re-applied to a fresh project without clicking through the console.

## Active policies

| File | What it catches |
|---|---|
| `alert-5xx.json` | Any 5xx response from the service in a 5-minute window. Covers backend exceptions, scheduler-driven failures (cron POST → 5xx), and IAM regressions. |
| `alert-latency.json` | p95 request latency above 30s for 5 minutes — leading indicator of Cloud Run timeouts (limit is 120s) before they actually fire. |

## Notification channel

`projects/awesome-ridge-495103-b2/notificationChannels/1025076736043142473`
(email: sooryong.byun@gmail.com — verification email sent at create time;
must be confirmed via the link before alerts deliver).

## Re-applying

```powershell
$TOKEN = gcloud auth print-access-token
$PROJECT = "awesome-ridge-495103-b2"
$CHANNEL = "projects/$PROJECT/notificationChannels/<ID>"

foreach ($f in Get-ChildItem -Filter alert-*.json) {
    $policy = Get-Content $f.FullName -Raw | ConvertFrom-Json
    $policy | Add-Member -NotePropertyName notificationChannels -NotePropertyValue @($CHANNEL) -Force
    $body = $policy | ConvertTo-Json -Depth 10
    Invoke-RestMethod -Method POST `
        -Uri "https://monitoring.googleapis.com/v3/projects/$PROJECT/alertPolicies" `
        -Headers @{ Authorization = "Bearer $TOKEN"; "Content-Type" = "application/json" } `
        -Body $body
}
```

## Gaps

- No dedicated Cloud Scheduler alert. A scheduler failure that actually
  reaches Cloud Run shows up as a 5xx and triggers `alert-5xx.json`. A
  scheduler failure that *can't reach* Cloud Run (DNS, IAM on the cron
  invoker) is currently not alerted on; if that becomes a real concern,
  add a log-based metric on `resource.type="cloud_scheduler_job"
  jsonPayload.@type=~"AuditLog"` and a policy referencing it.
- No Anthropic-specific error rate. Backend exceptions land in the 5xx
  alert. For finer-grained insight, instrument with structured log fields
  and create a log-based metric.
