## Description

This website describes my ontology based off of https://schema.org/. The website is public as I have multiple Logseq graphs that use this ontology and will link back to this website.

## Development

Install [babashka](https://github.com/babashka/babashka). Most useful bb tasks:
* `bb schema:create` - Creates posts and a classes page from index EDN files
* `bb schema:rebuild` - Rebuilds posts and html from index EDN files for local development
* `bb quickblog` - Invoke [quickblog](https://github.com/borkdude/quickblog) tasks

The `resources/*index.edn` files are generated from https://github.com/cldwalker/bb-clis.

## Contributing

I'm not looking for major contributions as this is my personal website. I'm sharing the code so others can see how they publish their own ontology.

## LLM Disclaimer

A fair amount of the code is built with LLM assistance.

## LICENSE

See LICENSE.md
