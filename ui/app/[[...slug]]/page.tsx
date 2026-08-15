import App from "../app";

export function generateStaticParams() {
  return [{ slug: [] as string[] }];
}

export default function Page() {
  return <App />;
}
