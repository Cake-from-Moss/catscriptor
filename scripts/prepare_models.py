#!/usr/bin/env python3
"""Подготовка зафиксированных моделей и AAR на компьютере разработчика."""

import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line.strip() and not line.startswith("#"))


def digest(path):
    with path.open("rb") as stream:
        result = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
        return result.hexdigest()


def download(url, expected, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and digest(destination) == expected:
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            print(f"Загрузка {url}", flush=True)
            with urllib.request.urlopen(url, timeout=120) as source:
                shutil.copyfileobj(source, output)
        if digest(temporary) != expected:
            raise ValueError(f"Неверный SHA-256: {url}")
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    model = properties(ROOT / "app/src/main/assets/model-manifest.properties")
    library = properties(ROOT / "dependencies/sherpa-onnx.properties")
    licenses = properties(ROOT / "dependencies/licenses.properties")
    for name in licenses["files"].split(","):
        download(licenses[f"{name}.url"], licenses[f"{name}.sha256"],
                 ROOT / "app/src/main/assets/third-party" / name)
    download(library["url"], library["sha256"], ROOT / "dependencies" / library["file"])
    archive = ROOT / ".model-cache" / Path(model["archive.url"]).name
    download(model["archive.url"], model["archive.sha256"], archive)
    destination = ROOT / "app/src/main/assets" / model["model.directory"]
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        for name in model["files"].split(","):
            target = destination / name
            if target.exists() and digest(target) == model[f"{name}.sha256"]:
                continue
            member = source.getmember(f"{model['archive.root']}/{name}")
            if not member.isfile() or member.size != int(model[f"{name}.size"]):
                raise ValueError(f"Неверный файл в архиве: {name}")
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=destination, delete=False) as output:
                    temporary = Path(output.name)
                    with source.extractfile(member) as stream:
                        shutil.copyfileobj(stream, output)
                if digest(temporary) != model[f"{name}.sha256"]:
                    raise ValueError(f"Неверный SHA-256: {name}")
                os.replace(temporary, target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        # Звуковой пример находится только в тестовом APK, не в основном приложении.
        sample = ROOT / "app/src/androidTest/assets/sherpa-example.wav"
        sample.parent.mkdir(parents=True, exist_ok=True)
        data = source.extractfile(f"{model['archive.root']}/test_wavs/example.wav").read()
        if hashlib.sha256(data).hexdigest() != model["test.wav.sha256"]:
            raise ValueError("Повреждён тестовый WAV")
        sample.write_bytes(data)
    print("AAR и обязательная ASR-модель подготовлены и проверены.")


if __name__ == "__main__":
    main()
