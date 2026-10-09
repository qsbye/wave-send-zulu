# LocalSend Web App

A web app integrating WebRTC and WebSockets to share files with other LocalSend peers (browsers, or native versions).

Live: https://web.localsend.org

## Setup

Make sure to install [pnpm](https://pnpm.io).

```bash
npm install -g pnpm
```

Get dependencies

```bash
pnpm install
```

Start the development server

```bash
pnpm run dev
```

## Deployment

Generates the static website in the `dist` directory.

```bash
pnpm run generate
```

### Self-hosting

1. Clone this repo
2. Build: `docker build --tag localsend-web --file Containerfile .`
3. Run: `docker run --rm --publish 8080:443 --volume caddy-data:/data localsend-web`

**Note**:
The web app requires a signaling server to work. By default, it uses the public signaling server at `wss://public.localsend.org/v1/ws`.
You can change this by setting the `SIGNALING_URL` environment variable during `pnpm run generate`.

To be fully self-hosted, you can also deploy your own [signaling server](https://github.com/localsend/localsend/tree/main/server).

## Contributing

### Adding a new language

1. Add new JSON file in `i18n/locales/` directory.
2. Add the new language in `nuxt.config.ts`.
