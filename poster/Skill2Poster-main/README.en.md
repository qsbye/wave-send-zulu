# Skill2Poster

[中文](README.md) | [English](README.en.md) | [Project page](https://leon-gittech.github.io/Skill2Poster/)

Skill2Poster is a Codex skill pack for academic poster generation. It helps turn research papers into accurate A0 HTML/PDF-style posters by using AI image generation only as a visual design reference, then rebuilding the final poster with verified paper content, figures, tables, equations, logos, QR codes, and measurements.

The project packages two Codex skills, example outputs, and a GitHub Pages-ready landing page that show a practical paper-to-poster workflow:

1. Generate a strong visual reference mockup for the poster.
2. Use that reference to build a high-resolution, accurate, editable HTML poster with verified paper content.

The core idea is that image generation is useful for design exploration, but it should not be trusted as the final factual artifact. Skill2Poster uses image generation to discover layout, visual hierarchy, density, color, and section balance, then rebuilds the poster in HTML with real text, figures, tables, logos, QR codes, and measurements.

## Who It Is For

- Researchers who want to turn a research paper, LaTeX project, or PDF into a conference-ready poster.
- Users who need an inspectable, exportable, dimension-aware HTML academic poster workflow.
- Teams that want AI poster mockups for layout exploration without trusting generated text, equations, tables, or numeric results.
- Codex users who want reusable skills for poster design, content auditing, HTML rendering, and layout QA.

## Motivation

Academic posters are hard because they combine two different jobs:

- content compression: deciding what argument, method, evidence, and result should survive from the paper;
- visual production: making that content readable, balanced, and conference-ready on an A0 canvas.

Systems such as [Paper2Poster](https://github.com/Paper2Poster/Paper2Poster) focus on paper-to-poster automation and commonly emphasize a top-down agent pipeline from a paper PDF to an editable poster artifact. Skill2Poster takes a complementary implementation-first route: it packages reusable skills that guide a human or Codex-style agent through design mockup generation, factual reconstruction, HTML rendering, and QA.

This separation matters because current image models can create visually compelling poster references, but they often distort exact text, equations, tables, QR codes, and numeric results. Skill2Poster treats the generated image as a design reference, not as the source of truth.

## What Is Included

```text
.
├── README.md
├── README.en.md
├── SHARE.md
├── references/
│   └── from-off-policy-to-on-policy.bib
├── docs/
│   ├── index.html
│   ├── robots.txt
│   └── sitemap.xml
├── academic-poster-builder/
│   ├── SKILL.md
│   ├── agents/openai.yaml
│   └── references/poster-qa-checklist.md
├── paper-poster-imagegen/
│   ├── SKILL.md
│   ├── agents/openai.yaml
│   └── references/prompt-template.md
├── assets/
│   ├── poster_image.png
│   └── poster_html.png
└── P2P.jpg
```

### `paper-poster-imagegen`

This skill creates and critiques image-generation prompts for academic poster reference mockups. It helps prepare a structured poster brief, specify section order, place metric cards, reserve areas for real figures and tables, and iterate on visual density before any HTML implementation.

### `academic-poster-builder`

This skill converts the poster concept into a factual A0 HTML/PDF/PPT-style artifact. It emphasizes source auditing, real paper assets, A0 dimensions, readable typography, panel alignment, overflow checks, and final rendering QA.

## Quick Start

1. Use `paper-poster-imagegen/` and `academic-poster-builder/` as Codex skills.
2. Run `paper-poster-imagegen` to prepare the poster brief and generate an A0 portrait design reference.
3. Run `academic-poster-builder` to rebuild the poster in HTML with verified paper content and real assets.
4. Render the final HTML/PDF/PNG and check dimensions, overflow, clipping, readability, and factual consistency.

Recommended GitHub topics:

```text
academic-poster, poster-generation, paper-to-poster, codex-skills,
ai-poster, research-poster, html-poster, image-generation,
scientific-visualization, conference-poster
```

## Example Paper Citation

The example poster images in this repository use the following paper as the demonstration subject:

- Paper: *From Off-Policy to On-Policy: Enhancing GUI Agents via Bi-level Expert-to-Policy Assimilation*
- arXiv: <https://arxiv.org/abs/2601.05787>
- BibTeX: [references/from-off-policy-to-on-policy.bib](references/from-off-policy-to-on-policy.bib)

## Required Inputs

Skill2Poster works best when the following inputs are available:

- paper source: PDF, LaTeX project, abstract, method, results, conclusion, and appendix references;
- visual assets: original paper figures, tables, equations, logos, QR codes, screenshots, and any prior poster draft;
- result facts: headline metrics, baseline comparisons, section claims, limitations, and captions;
- venue constraints: A0 portrait target, conference mark, institution branding, author list, and required footer or QR conventions;
- optional reference: a draft poster image, visual mood board, or existing HTML/PPT/PDF poster output.

When exact values are missing, the workflow should use clear placeholders during image generation and replace them with verified content during HTML implementation.

## Pipeline

### 1. Audit the source content

Start from the paper and local assets. Identify the central claim, required figures, core equations, main result tables, and any venue-specific metadata. This prevents the poster from becoming a decorative summary with invented details.

### 2. Build a poster brief

Create a compact brief containing:

- title, subtitle, authors, affiliations, and logos;
- four top metric cards;
- one thesis sentence;
- section order and purpose;
- required figures and preferred placement;
- QR codes and links;
- negative constraints from prior drafts.

### 3. Generate a design reference image

Use `paper-poster-imagegen` to produce a high-fidelity A0 portrait mockup. The target is a visually plausible poster with a readable three-column structure, strong header hierarchy, metric strip, thesis strip, and figure/table placeholders.

Example design reference:

![Skill2Poster design reference](assets/poster_image.png)

### 4. Critique and revise the reference

Reject mockups that contain tiny text, fake charts, duplicated tables, cropped figures, random sections, decorative footers, or large accidental blank space. Iterate until the reference is useful as an implementation guide.

### 5. Rebuild as high-resolution HTML

Use `academic-poster-builder` to implement the poster as an A0 HTML canvas:

- physical target: `841mm x 1189mm`;
- CSS canvas: `3179px x 4494px` at 96 dpi;
- 150 dpi PNG export: about `4967px x 7022px`;
- real paper text, figures, tables, logos, QR codes, and equations;
- no reliance on generated text or generated numbers.

Example HTML export:

![Skill2Poster HTML export](assets/poster_html.png)

### 6. Render and verify

Run layout QA before delivery:

- confirm the page dimensions;
- check panel overflow;
- verify readable typography;
- ensure figures and tables are not clipped;
- align column bottoms;
- inspect the final PNG/PDF visually.

## Comparison With Paper2Poster

Paper2Poster is a paper-to-poster automation project that frames poster generation as a multi-agent pipeline from paper content to an editable poster artifact. Its GitHub repository is available at [Paper2Poster/Paper2Poster](https://github.com/Paper2Poster/Paper2Poster).

Skill2Poster is narrower and more implementation-oriented:

| Aspect | Paper2Poster | Skill2Poster |
| --- | --- | --- |
| Primary goal | End-to-end paper-to-poster automation | Reusable skills for high-quality poster construction |
| Design strategy | Agentic poster generation from paper inputs | Generate a design reference first, then rebuild accurately in HTML |
| Source of truth | Paper and agent-produced poster artifact | Paper, local assets, verified metrics, and rendered HTML |
| Image generation role | Part of poster-generation workflow | Design exploration only, never factual authority |
| Final implementation focus | Editable poster output | High-resolution, measured A0 HTML/PDF-style output |

Paper2Poster-style example:

![Paper2Poster example](P2P.jpg)

## Expected Output

The intended final artifact is not just a good-looking poster screenshot. It should be:

- visually strong enough to match a top-conference poster standard;
- factually grounded in the paper and local assets;
- rendered at A0-compatible dimensions;
- inspectable as HTML;
- exportable to high-resolution PNG/PDF;
- checked for overflow, clipping, and readability.

Skill2Poster is therefore best understood as a bridge between fast visual ideation and reliable poster implementation.
