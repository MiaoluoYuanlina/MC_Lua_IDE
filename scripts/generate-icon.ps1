# Draws the MC Lua IDE icon as 32x32 pixel art and writes it scaled up with hard pixel edges.
# A Minecraft-style block: grass on top (with window buttons), a code editor on the front face, the Lua moon in the corner.
# Usage: powershell -NoProfile -File scripts/generate-icon.ps1 [-Out path] [-Preview path]
# (Helpers are not called R: in Windows PowerShell that is the alias of Invoke-History.)
param(
    [string]$Out = "$PSScriptRoot\..\src\main\resources\assets\mc_lua_ide\icon.png",
    [string]$Preview = ""
)
Add-Type -AssemblyName System.Drawing
$N = 32
$img = New-Object System.Drawing.Bitmap $N, $N
function C([string]$hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }
function Px([int]$x, [int]$y, [string]$hex) { if ($x -ge 0 -and $y -ge 0 -and $x -lt $N -and $y -lt $N) { $img.SetPixel($x, $y, (C $hex)) } }
function Box([int]$x0, [int]$y0, [int]$x1, [int]$y1, [string]$hex) { for ($y = $y0; $y -le $y1; $y++) { for ($x = $x0; $x -le $x1; $x++) { Px $x $y $hex } } }

$outline = '#0e1018'; $body = '#1c2130'; $gutter = '#242a3b'; $shade = '#151925'
$grass = '#5fae36'; $grassDark = '#3f8a25'; $grassLight = '#8fd14f'; $grassTop = '#a6e061'
$num = '#4c5670'; $kw = '#c678dd'; $fn = '#61afef'; $str = '#e5c07b'; $txt = '#c8d0e0'; $cmt = '#6a8f4e'; $caret = '#ffffff'

# Silhouette: block with clipped corners and a dark outline.
Box 1 1 30 30 $outline
foreach ($c in @(@(1,1),@(30,1),@(1,30),@(30,30))) { $img.SetPixel($c[0], $c[1], [System.Drawing.Color]::Transparent) }
Box 2 2 29 29 $body

# Front face: gutter, line numbers, code, and depth shading at the bottom and right.
Box 2 8 5 29 $gutter
Box 2 29 29 29 $shade; Box 29 8 29 29 $shade
foreach ($y in @(11,14,17,20,23,26)) { Box 3 $y 4 $y $num }
Box 7 11 10 11 $kw;  Box 12 11 15 11 $fn;  Box 16 11 17 11 $txt     # local function greet()
Box 9 14 11 14 $kw;  Box 13 14 14 14 $txt; Box 15 14 19 14 $str     #   local t = "hi"
Box 9 17 12 17 $fn;  Box 13 17 13 17 $txt                           #   print(t)
Box 9 20 13 20 $cmt                                                 #   -- comment
Box 7 23 9 23 $kw                                                   # end
Box 7 26 7 27 $caret                                                # caret

# Grass top: a bright top edge, fixed speckles, then uneven drips over the front face like a grass block's side.
Box 2 2 29 7 $grass
for ($x = 2; $x -le 29; $x++) { if ($x % 3 -ne 0) { Px $x 2 $grassTop } }
foreach ($s in @(@(7,3),@(16,4),@(25,3),@(28,5),@(5,6),@(14,6),@(19,5),@(23,5),@(27,6),@(12,3),@(20,3))) { Px $s[0] $s[1] $grassLight }
foreach ($s in @(@(13,4),@(17,6),@(22,4),@(26,4),@(29,3),@(8,6),@(11,5),@(24,6))) { Px $s[0] $s[1] $grassDark }
$drip = @(1,2,1,0,1,2,3,1,0,1,2,1,1,0,2,3,2,1,0,1,1,2,1,0,2,1,2,1)   # depth below row 7 for x = 2..29
for ($i = 0; $i -lt $drip.Count; $i++) {
    $x = 2 + $i
    for ($k = 1; $k -le $drip[$i]; $k++) { Px $x (7 + $k) ($(if ($k -eq $drip[$i]) { $grassDark } else { $grass })) }
}

# Window buttons on the grass (the IDE title bar), each with a small dark rim below.
Box 3 3 4 4 '#ff5f57'; Box 6 3 7 4 '#febc2e'; Box 9 3 10 4 '#28c840'

# The Lua moon: an outlined blue disc with the white moon inside, overlapping the face.
$cx = 21.5; $cy = 21.5; $r = 6.3
for ($y = 13; $y -le 30; $y++) { for ($x = 13; $x -le 30; $x++) {
    $d = [Math]::Sqrt(($x - $cx) * ($x - $cx) + ($y - $cy) * ($y - $cy))
    if ($d -le $r + 1.05 -and $d -gt $r) { Px $x $y $outline }
    elseif ($d -le $r) {
        $t = (($x - $cx) + ($y - $cy)) / ($r * 2)
        if ($t -lt -0.30) { Px $x $y '#5b74f0' } elseif ($t -gt 0.28) { Px $x $y '#1f2f94' } else { Px $x $y '#2f45c4' }
    }
} }
$mx = 23; $my = 19; $mr = 2.3
for ($y = 14; $y -le 23; $y++) { for ($x = 20; $x -le 29; $x++) {
    $d = [Math]::Sqrt(($x - $mx) * ($x - $mx) + ($y - $my) * ($y - $my))
    if ($d -le $mr) { Px $x $y '#ffffff' }
} }
Px 22 18 '#e8edff'; Px 17 19 '#9db0ff'; Px 16 20 '#9db0ff'

function Save-Scaled([int]$size, [string]$path) {
    $big = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($big)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.DrawImage($img, 0, 0, $size, $size)
    $g.Dispose()
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $big.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $big.Dispose()
}
Save-Scaled 128 $Out
if ($Preview) { Save-Scaled 512 $Preview }
"icon written: $Out"
