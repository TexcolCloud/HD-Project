$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $PSCommandPath
$archive = Join-Path $base 'apache-maven-3.9.12-bin.zip'
$uri = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.12/apache-maven-3.9.12-bin.zip'
Invoke-WebRequest -Uri $uri -OutFile $archive
$expected = (Invoke-WebRequest -Uri ($uri + '.sha512')).Content.Trim().Split(' ')[0]
$actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash
if ($expected.ToLowerInvariant() -ne $actual.ToLowerInvariant()) { throw 'Maven SHA-512 checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $base -Force
