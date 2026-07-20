$ErrorActionPreference = "Stop"

Push-Location (Join-Path $PSScriptRoot "..")
try {
  .\mvnw.cmd spring-boot:run `
    "-Dspring-boot.run.arguments=--spring.config.import=optional:file:PreTreatment/config/pretreatment.yml --spring.main.web-application-type=none --app.ai.pretreatment.cli.enabled=true --app.ai.pretreatment.cli.mode=apply --app.ai.pretreatment.cli.dry-run=false"
} finally {
  Pop-Location
}
