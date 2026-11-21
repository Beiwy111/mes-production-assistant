# Third-party notices

## T3 Code

The production collaboration workbench adapts visual layout and design ideas from https://github.com/pingdotgg/t3code. It uses an independent Vue implementation, not the React application or Node.js backend. Source references are documented in docs/superpowers/plans/2026-11-14-t3-workbench-rebuild.md.

MIT License

Copyright (c) 2026 T3 Tools Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


## Other packages

Lucide Vue icons, Inter Variable, JetBrains Mono Variable, marked and DOMPurify retain their respective license notices in the installed packages and package lockfile. Fonts are served locally; no external font CDN is required.

## MES Workspace local font subsets (2026-11-15)

The workspace serves the existing Inter Latin variable subset (48,256 bytes) as `MES Inter` and JetBrains Mono Latin variable subset (40,404 bytes) as `MES Mono`. Both are SIL Open Font License 1.1; full notices ship in `public/licenses/Inter-OFL.txt` and `public/licenses/JetBrainsMono-OFL.txt`. Package font binaries are unmodified. NIGHTFALL is a screenshot-only visual-quality reference; its source, artwork, brand, and assets are not copied.

Chinese typography now uses the unmodified `@fontsource-variable/noto-sans-sc` 5.3.1 package, `Noto Sans SC Variable`, with its real 100–900 weight axis. Source: https://fontsource.org/fonts/noto-sans-sc and https://github.com/notofonts/noto-cjk. The full SIL OFL-1.1 notice ships in `public/licenses/NotoSansSC-OFL.txt`. The 101 WOFF2 Unicode-range subsets total 4,500,544 bytes on disk; the browser requests only subsets needed by rendered characters, sharing the same files across weights. Fonts are self-hosted and require no new font CDN connection. Microsoft YaHei UI/PingFang SC remain fallbacks. This font stack is scoped to the assistant; existing global font imports on other MES screens are unchanged.

