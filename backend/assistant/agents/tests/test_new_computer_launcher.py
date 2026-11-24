"""Exercise the shipped Windows launcher in a checkout with spaces and isolated stage doubles."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import venv
from pathlib import Path

DEPLOY = Path(__file__).resolve().parents[2] / "deploy"


@unittest.skipUnless(os.name == "nt" and sys.version_info[:2] == (3, 13), "Windows Python 3.13 launcher")
class NewComputerLauncherTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="MES team checkout ")
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        self.deploy = root / "backend/assistant/deploy"
        self.deploy.mkdir(parents=True)
        self.log = root / "stages.log"
        self.launch = self.deploy / "Initialize-Assistant.ps1"
        shutil.copyfile(DEPLOY / self.launch.name, self.launch)
        bin_dir = root / "tools"
        bin_dir.mkdir()
        for tool, result in {"docker": "linux", "javac": "javac 17.0.16",
                             "java": "", "mvn": "", "git": ""}.items():
            (bin_dir / (tool + ".cmd")).write_text("@echo off\n" + (f"echo {result}\n" if result else "") + "exit /b 0\n")
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], MES_STAGE_LOG=str(self.log))
        venv.EnvBuilder(with_pip=False).create(self.deploy.parent / "rag/.venv")
        self.steps = ["Initialize-KnowledgeLocal.ps1", "Initialize-MesAssistant.py", "Prepare-DifyLocal.ps1",
                      "Configure-DifyProvider.py", "Provision-Stage5Dify.py", "Build-AssistantRuntime.ps1",
                      "Start-AssistantLocal.ps1", "Verify-NewComputer.py"]
        for name in self.steps:
            if name.endswith(".py"):
                source = (f"import os, sys\nwith open(os.environ['MES_STAGE_LOG'], 'a', encoding='ascii') as f: f.write('{name}\\n')\n"
                          f"sys.exit(1 if os.getenv('MES_STAGE_FAIL') == '{name}' else 0)\n")
            else:
                source = f"param([string]$Python,[switch]$Start,[int]$TimeoutSeconds)\nAdd-Content $env:MES_STAGE_LOG '{name}' -Encoding ascii\n"
                if name == "Start-AssistantLocal.ps1":
                    source += f"if ($env:MES_STAGE_FAIL -eq '{name}') {{ exit 1 }}\n"
            (self.deploy / name).write_text(source, encoding="ascii")

    def launch_stages(self, *options, fail=""):
        self.log.unlink(missing_ok=True)
        result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                 str(self.launch), "-Python", sys.executable, *options],
                                env=dict(self.env, MES_STAGE_FAIL=fail), capture_output=True, timeout=90)
        seen = self.log.read_text(encoding="ascii").splitlines() if self.log.exists() else []
        return result.returncode, seen

    def test_order_resume_and_failure_boundaries(self):
        self.assertEqual(self.launch_stages("-CheckOnly"), (0, []))
        for name in ["Provision-Stage5Dify.py", "Start-AssistantLocal.ps1"]:
            with self.subTest(name=name):
                self.assertEqual(self.launch_stages(fail=name), (1, self.steps[:self.steps.index(name) + 1]))
        self.assertEqual(self.launch_stages(), (0, self.steps))


if __name__ == "__main__":
    unittest.main()
