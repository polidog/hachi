/*
 * 手書きドキュメント共通の組み立て。#doc-source の Markdown を #doc に流し込み、
 * 上の帯（ページ間の行き来）を作る。ページを 1 本足したら PAGES に 1 行足す。
 */
const PAGES = [
  ["index.html", "索引"],
  ["architecture.html", "アーキテクチャ"],
  ["roadmap.html", "実装計画"],
  ["test-architecture.html", "テスト"],
  ["system-model.html", "システムモデル"],
];

const here = location.pathname.split("/").pop() || "index.html";

const bar = document.createElement("nav");
bar.className = "bar";
bar.innerHTML =
  '<span class="bar__label">HACHI // DOCS</span>' +
  PAGES.map(([href, name]) =>
    `<a href="${href}"${href === here ? ' aria-current="page"' : ""}>${name}</a>`
  ).join("");
document.body.prepend(bar);

// システムモデルのページには Markdown の本文がない。帯だけ作って終わる。
const source = document.getElementById("doc-source");
const target = document.getElementById("doc");
if (source && target) target.innerHTML = marked.parse(source.textContent.trim());
