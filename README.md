# ExHentai Watched & Popular Test

Tachimanga extension repository: [https://raw.githubusercontent.com/pujx233/cursed-manga-extensions/exhentai-test-repo/index.min.json](https://raw.githubusercontent.com/pujx233/cursed-manga-extensions/exhentai-test-repo/index.min.json)

Version: **1.4.32**. This repository contains only the E-Hentai extension, with one source for all languages. It retains the former Chinese source's ID for existing library entries.

- Popular loads the website's `/popular` list.
- Search filters → Gallery list → Watched tags loads the website's `/watched` list using the account configured in the extension.
- Gallery list also offers All galleries and Favorites as mutually exclusive choices.
- The language dropdown defaults to all languages and filters by the website's language tags when a language is selected.
- Pagination follows the website's Next URL, with separate cursors for search and latest updates.
- Gallery parsing supports the website's table and thumbnail layouts.
- Chapter upload dates come from the gallery's Posted field, parsed as UTC; they are no longer left at the app's default date.

[Source branch](https://github.com/pujx233/cursed-manga-extensions/tree/feat/exhentai-watched-popular) · [Exact source commit](https://github.com/pujx233/cursed-manga-extensions/tree/0be51504e0f6b63968e73ded781a1064e767021b)

Based on [cursed-manga-extensions](https://github.com/yuzono/cursed-manga-extensions). Source and binaries are distributed under the included Apache License 2.0.

Validation: release assembly, release lint, formatting checks and 11 unit tests passed. The website's Posted field was checked against its official metadata API, and language filtering was checked on the live Watched page. Installation and browsing in Tachimanga on an iPhone still require device testing.
