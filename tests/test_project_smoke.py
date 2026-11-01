from pathlib import Path
import ast

ROOT = Path(__file__).resolve().parents[1]


def test_required_project_files_exist():
    required = (
        "README.md",
        "requirements.txt",
        "ybt_app.py",
        "ybt_brand.py",
        "run_you_b_tech.bat",
        "YBT-Army-135M",
    )
    for name in required:
        assert (ROOT / name).exists(), f"Required project path is missing: {name}"


def test_python_source_files_parse():
    ignored = {".git", ".venv", "venv", "__pycache__"}
    files = [
        path for path in ROOT.rglob("*.py")
        if not any(part in ignored for part in path.parts)
    ]
    assert files, "No Python source files were found"
    for path in files:
        ast.parse(path.read_text(encoding="utf-8"), filename=str(path))


def test_required_runtime_dependencies_are_declared():
    requirements = ROOT / "requirements.txt"
    declared = set()
    for line in requirements.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        package = line.split(";", 1)[0].split("[", 1)[0]
        for separator in ("==", ">=", "<=", "~=", "!=", ">", "<"):
            package = package.split(separator, 1)[0]
        declared.add(package.strip().lower())
    for package in ("streamlit", "torch", "transformers"):
        assert package in declared, f"Missing runtime dependency: {package}"
