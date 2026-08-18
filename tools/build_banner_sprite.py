from pathlib import Path
import sys

from PIL import Image


FRAME_WIDTH = 162
FRAME_HEIGHT = 18
FRAME_COUNT = 12


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: build_banner_sprite.py SOURCE OUTPUT")

    source_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    source = Image.open(source_path).convert("RGB")
    width, height = source.size

    frames: list[Image.Image] = []
    for index in range(FRAME_COUNT):
        top = round(index * height / FRAME_COUNT)
        bottom = round((index + 1) * height / FRAME_COUNT)
        strip = source.crop((0, top, width, bottom))
        strip = strip.resize((FRAME_WIDTH, FRAME_HEIGHT), Image.Resampling.LANCZOS)
        # Keep the generated pixel-art palette crisp in Minecraft's tiny UI slot.
        strip = strip.quantize(colors=96, method=Image.Quantize.MEDIANCUT).convert("RGBA")
        frames.append(strip)

    sheet = Image.new("RGBA", (FRAME_WIDTH, FRAME_HEIGHT * FRAME_COUNT))
    for index, frame in enumerate(frames):
        sheet.paste(frame, (0, index * FRAME_HEIGHT))

    output_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output_path, optimize=True)


if __name__ == "__main__":
    main()
