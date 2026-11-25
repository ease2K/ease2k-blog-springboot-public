"""Copy the credentials and uploaded images shared by migration and restore."""

from contextlib import contextmanager
from pathlib import Path
import re
import shutil
import tempfile


MEDIA_NAME = re.compile(r"[a-f0-9-]{36}\.webp")


def source_files(editor):
    owner = editor / "owner.json"
    if owner.exists() or owner.is_symlink():
        yield owner
    media = editor / "media"
    if media.is_symlink():
        raise ValueError("图片目录不能是符号链接。")
    if media.is_dir():
        yield from (path for path in media.iterdir() if MEDIA_NAME.fullmatch(path.name))


def copy_files(editor, target, missing_media_ok=False):
    (target / "media").mkdir()
    for path in source_files(editor):
        if path.is_symlink() or not path.is_file():
            if missing_media_ok and path.parent == editor / "media" and not path.exists() and not path.is_symlink():
                continue
            raise ValueError("站主凭据和图片必须是普通文件。")
        try:
            shutil.copyfile(path, target / path.relative_to(editor))
        except FileNotFoundError:
            if not missing_media_ok or path.parent != editor / "media":
                raise


@contextmanager
def staged_editor(source, target):
    if target.exists() or target.is_symlink():
        raise ValueError("目标目录已存在，请指定新目录。")
    if source in target.resolve().parents:
        raise ValueError("目标目录不能位于源目录内。")
    with tempfile.TemporaryDirectory(prefix=".editor-", dir=target.parent) as temporary:
        stage = Path(temporary) / "editor"
        stage.mkdir(mode=0o700)
        copy_files(source, stage)
        yield stage
        stage.rename(target)
