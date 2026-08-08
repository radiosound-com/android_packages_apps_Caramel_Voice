#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-zipformer.XXXXXX")
trap 'rm -rf -- "$work_dir"' EXIT

model_name=sherpa-onnx-streaming-zipformer-en-2023-06-21
model_url=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${model_name}.tar.bz2
archive="$work_dir/${model_name}.tar.bz2"
destination="$repo_root/app/model/$model_name"

curl --fail --location --output "$archive" "$model_url"
echo '455f40e556aa2b20ac9d3bffd603b58002075c1193b4070938540c11efe0a4da  '"$archive" \
    | shasum -a 256 -c -
tar -xjf "$archive" -C "$work_dir"

python3 -m venv "$work_dir/venv"
"$work_dir/venv/bin/python" -m pip install --disable-pip-version-check \
    'sentencepiece==0.2.2'
tar -xzf "$repo_root/provenance/sources/sherpa-onnx-v1.13.4-142807252687d81b40d6315f23470a1512a00de3.tar.gz" \
    -C "$work_dir"
"$work_dir/venv/bin/python" \
    "$work_dir/sherpa-onnx-v1.13.4/scripts/export_bpe_vocab.py" \
    --bpe-model "$work_dir/$model_name/bpe.model"

mkdir -p "$destination"
for filename in \
    encoder-epoch-99-avg-1.int8.onnx \
    decoder-epoch-99-avg-1.onnx \
    joiner-epoch-99-avg-1.int8.onnx \
    tokens.txt \
    bpe.vocab \
    README.md; do
    install -m 0644 "$work_dir/$model_name/$filename" "$destination/$filename"
done

cd "$destination"
shasum -a 256 -c <<'HASHES'
eca6e2608d835c6b7ad596b659006dab216ab364928358a96d414e3e1ea4de04  README.md
f191a4935f668fa8cd8e607bcd378404f948321cd3134a5ea13d324ba921673d  bpe.vocab
9da02b77cb08826756ec6a88635f35a40374e4164e7c6359121a9145958a6ceb  decoder-epoch-99-avg-1.onnx
32c98281c7bd8b63e3e142d007251b37f120572e8fdea9a4f5a79ce22b10ec4f  encoder-epoch-99-avg-1.int8.onnx
831477d390e59a61f1b6a6f763b9903e6c6366ff6034f1ddba613be82637122f  joiner-epoch-99-avg-1.int8.onnx
49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb  tokens.txt
HASHES
