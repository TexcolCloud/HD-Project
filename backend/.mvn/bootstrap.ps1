$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$name = 'apache-maven-3.9.12'
$archive = Join-Path $base "$name-bin.zip"
$uri = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.12/$name-bin.zip"
$expected = '0ef0d074761a55e7b982fd7f2cd6ea2da028289c73156ebd488c0513efbff8a64ac5bc0c11c3b8bbced930ce15238ecef344b0e065ae54f7cdca8c86cf39e736'
$stage = Join-Path $base "$name-$([Guid]::NewGuid())"
$distribution = Join-Path $base $name
$launcher = Join-Path $distribution 'bin/mvn.cmd'

New-Item -ItemType Directory -Path $stage | Out-Null
try {
    if (!(Test-Path -LiteralPath $archive -PathType Leaf) -or
        (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $expected) {
        $download = Join-Path $stage "$name-bin.zip"
        Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $download
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA512).Hash -ne $expected) {
            throw 'Maven SHA-512 checksum mismatch'
        }
        Move-Item -LiteralPath $download -Destination $archive -Force
    }
    if (!(Test-Path -LiteralPath $launcher -PathType Leaf)) {
        Expand-Archive -LiteralPath $archive -DestinationPath $stage
        try {
            [IO.Directory]::Move((Join-Path $stage $name), $distribution)
        } catch [IO.IOException] {
            if (!(Test-Path -LiteralPath $launcher -PathType Leaf)) { throw }
        }
    }
} finally {
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($stage)) -eq $base) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
