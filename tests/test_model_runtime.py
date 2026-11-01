"""Opt-in runtime tests that load the real local YBT Army model.

Run with: python -m pytest -v tests/test_model_runtime.py
The CI workflow enables these only after Git LFS has downloaded the weights.
"""
from pathlib import Path

import pytest
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "YBT-Army-135M"
WEIGHTS = MODEL_DIR / "model.safetensors"


@pytest.fixture(scope="module")
def loaded_model():
    assert MODEL_DIR.is_dir(), f"Model directory is missing: {MODEL_DIR}"
    assert WEIGHTS.is_file(), f"Model weights are missing: {WEIGHTS}"
    assert WEIGHTS.stat().st_size > 1_000_000, (
        "model.safetensors is a Git LFS pointer, not the downloaded model weights. "
        "Run git lfs pull or enable LFS in the workflow checkout."
    )

    torch.set_num_threads(min(4, torch.get_num_threads()))
    tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR, local_files_only=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_DIR,
        torch_dtype=torch.float32,
        local_files_only=True,
    )
    model.eval()
    return model, tokenizer


def test_tokenizer_loads_and_has_chat_template(loaded_model):
    _, tokenizer = loaded_model
    assert tokenizer.vocab_size == 49152
    assert tokenizer.chat_template, "The model tokenizer must expose its chat template."


def test_model_loads_with_expected_architecture(loaded_model):
    model, _ = loaded_model
    assert model.config.model_type == "llama"
    assert model.config.num_hidden_layers == 30
    assert model.config.hidden_size == 576


def test_model_runs_a_short_greedy_inference(loaded_model):
    model, tokenizer = loaded_model
    prompt = tokenizer.apply_chat_template(
        [
            {"role": "system", "content": "You are a helpful assistant. Be concise."},
            {"role": "user", "content": "Say hello."},
        ],
        add_generation_prompt=True,
        return_tensors="pt",
    )
    assert prompt.ndim == 2 and prompt.shape[1] > 0

    with torch.inference_mode():
        output = model.generate(
            prompt,
            max_new_tokens=8,
            do_sample=False,
            pad_token_id=tokenizer.pad_token_id,
            eos_token_id=tokenizer.eos_token_id,
        )

    assert output.shape[0] == 1
    assert output.shape[1] > prompt.shape[1], "Inference did not generate any new tokens."
    generated = tokenizer.decode(output[0, prompt.shape[1]:], skip_special_tokens=True)
    assert isinstance(generated, str)
