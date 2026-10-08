# Original vector icon rendered using Windows built-in System.Drawing.
# Run: powershell.exe -NoProfile -ExecutionPolicy Bypass -File assets/generate-icon.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$navy = [Drawing.ColorTranslator]::FromHtml('#102e3b')
$teal = [Drawing.ColorTranslator]::FromHtml('#3dd9b6')
$white = [Drawing.ColorTranslator]::FromHtml('#f2faf8')

function New-IconBitmap([int]$size) {
  $bitmap = [Drawing.Bitmap]::new($size, $size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $graphics = [Drawing.Graphics]::FromImage($bitmap)
  $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $graphics.ScaleTransform($size / 256.0, $size / 256.0)
  $background = [Drawing.Drawing2D.GraphicsPath]::new()
  $background.AddArc(0, 0, 104, 104, 180, 90)
  $background.AddArc(152, 0, 104, 104, 270, 90)
  $background.AddArc(152, 152, 104, 104, 0, 90)
  $background.AddArc(0, 152, 104, 104, 90, 90)
  $background.CloseFigure()
  $brush = [Drawing.SolidBrush]::new($navy)
  $graphics.FillPath($brush, $background)
  $pen = [Drawing.Pen]::new($teal, 14)
  $pen.StartCap = $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
  $pen.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
  foreach ($points in @(
    @([Drawing.PointF]::new(87,48), [Drawing.PointF]::new(48,48), [Drawing.PointF]::new(48,87)),
    @([Drawing.PointF]::new(169,48), [Drawing.PointF]::new(208,48), [Drawing.PointF]::new(208,87)),
    @([Drawing.PointF]::new(48,169), [Drawing.PointF]::new(48,208), [Drawing.PointF]::new(87,208)),
    @([Drawing.PointF]::new(169,208), [Drawing.PointF]::new(208,208), [Drawing.PointF]::new(208,169))
  )) { $graphics.DrawLines($pen, [Drawing.PointF[]]$points) }
  $pen.Color = $white
  $pen.Width = 15
  $graphics.DrawLines($pen, [Drawing.PointF[]]@([Drawing.PointF]::new(91,168), [Drawing.PointF]::new(128,87), [Drawing.PointF]::new(165,168)))
  $graphics.DrawLine($pen, 104, 141, 152, 141)
  $pen.Dispose(); $brush.Dispose(); $background.Dispose(); $graphics.Dispose()
  return $bitmap
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$entries = [Collections.Generic.List[byte[]]]::new()
foreach ($size in $sizes) {
  $bitmap = New-IconBitmap $size
  $stream = [IO.MemoryStream]::new()
  try {
    $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png)
    $entries.Add($stream.ToArray())
    if ($size -eq 256) { $bitmap.Save((Join-Path $PSScriptRoot 'lens.png'), [Drawing.Imaging.ImageFormat]::Png) }
  } finally { $stream.Dispose(); $bitmap.Dispose() }
}
$output = [IO.File]::Create((Join-Path $PSScriptRoot 'lens.ico'))
$writer = [IO.BinaryWriter]::new($output)
try {
  $writer.Write([UInt16]0); $writer.Write([UInt16]1); $writer.Write([UInt16]$sizes.Count)
  $offset = 6 + 16 * $sizes.Count
  for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dimension = if ($sizes[$i] -eq 256) { 0 } else { $sizes[$i] }
    $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
    $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([UInt16]1); $writer.Write([UInt16]32)
    $writer.Write([UInt32]$entries[$i].Length); $writer.Write([UInt32]$offset)
    $offset += $entries[$i].Length
  }
  foreach ($entry in $entries) { $writer.Write($entry) }
} finally { $writer.Dispose(); $output.Dispose() }
Write-Output ('Created lens.ico with ' + $sizes.Count + ' sizes and lens.png')
