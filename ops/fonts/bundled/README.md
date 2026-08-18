# Bundled fonts

Drop **Pretendard** static instances here before building the conversion worker
image. They are not committed to this repository: a font binary in git history
is awkward to remove later if a licence question ever arises, even for an OFL
face.

Fetch the release from <https://github.com/orioncactus/pretendard> and copy:

    Pretendard-Regular.otf
    Pretendard-Medium.otf
    Pretendard-SemiBold.otf
    Pretendard-Bold.otf

Use the **static instances, not PretendardVariable**. Variable-font subsetting
through fontkit/pdf-lib is the fussier path, and for archived 결재 documents
determinism matters more than file size.

Licence: SIL Open Font License 1.1 — free, redistributable and commercially
usable, which is why it can ship in both the managed and on-prem builds without
a per-client licence conversation.

## What must never go here

**함초롬바탕 / 함초롬돋움 are Hancom-licensed and must never be bundled.** A
client who owns 한글 installs them through the font manager, under their own
licence and their own acknowledgement. That is the supported answer, not a
limitation.
