# Sound import fixture

`silence.ogg` is 0.05 seconds of synthetic silence, generated locally with FFmpeg's native Vorbis encoder. It contains no Minecraft assets or third-party audio.

Generation arguments: `-f lavfi -i anullsrc=r=22050:cl=stereo -t 0.05 -c:a vorbis -strict -2 silence.ogg`.
