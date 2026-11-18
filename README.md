# 📦 Inventário

App Android simples e offline para inventariar suas coisas: **armazéns** (caixas, baldes,
cômodos, armários, gavetas… podendo um ficar dentro do outro) e os **itens** dentro deles.

Sem servidor. Os dados ficam só no aparelho, com exportação e backup opcional no S3.

## Funcionalidades

- **Tela inicial (painel)**: valor total, contadores (a devolver, estoque baixo, zerados,
  movimentações) e gráficos — valor/itens por categoria, itens por armazém, estado dos itens
  e atividade dos últimos 14 dias; listas de pendências e itens recentes
- **Importar produtos por JSON** (veja `exemplos/inventario-exemplo.json` ou baixe pelo app):
  o armazém é identificado pelo nome ou caminho (`Garagem > Armário`); se não vier ou não for
  encontrado, o app pergunta onde guardar — usar um existente, criar o armazém, ou criá-lo dentro
  de outro. Itens iguais podem ter a quantidade somada.

- **Armazéns** aninhados (ex.: Garagem › Armário › Caixa A), com tipo, localização e observação
- **Itens** com quantidade (aceita decimais), unidade de medida, **valor unitário (R$)**, estado, categoria,
  quantidade mínima (alerta de estoque baixo) e observação
- **Busca** instantânea (ignora acentos) por nome, categoria, observação, local ou pessoa;
  filtros: emprestados, estoque baixo, zerados, por estado
- **Retirar**: “vai voltar” (fica pendente de devolução, com quem/para quê) ou “baixa definitiva”
- **Devolver**: parcial ou total, para o armazém de origem ou outro, podendo atualizar o estado
- **Adicionar** quantidade, **transferir** (total ou parcial, mescla com item igual no destino),
  **duplicar**, editar, excluir
- **Fotos** nos itens e armazéns (câmera ou galeria, várias por item): salvas em **WebP**
  (≈1280 px, ~60–150 KB cada, mais miniatura de 320 px), com visualizador, foto de capa e
  miniatura opcional no PDF. A câmera não pede permissão (o app não declara CAMERA).
- **Histórico** completo de movimentações
- **Imprimir / PDF**: todos os itens, um armazém (com ou sem os de dentro), itens selecionados
  ou o resultado da busca — com opção de valores, estado, categoria, observações, agrupamento
  por armazém e coluna de conferência ☐. No Android use “Salvar como PDF” no diálogo de impressão.
- **Valores**: total por item, por armazém e geral
- **Backup**: exportar/importar `.json` com ou sem as fotos (pode salvar direto no Google Drive
  pelo seletor) e **backup no S3** (fotos vão em `fotos/<id>.webp`, enviadas uma única vez) (AWS, Cloudflare R2, MinIO, Backblaze B2, Wasabi…)
  manual ou automático ao sair do app
- Tema claro/escuro automático

## Baixar / instalar

Cada push gera o APK pelo GitHub Actions (aba **Actions** → execução → *Artifacts*).
Em push na branch padrão (ou rodando o workflow manualmente) é criada uma **Release**
com o APK — abra a página de *Releases* no celular e baixe o `.apk`.

### Atualizações sem desinstalar

Todas as versões instalam por cima da anterior, mantendo os dados, porque:

1. O `applicationId` é fixo (`app.inventario.pessoal`) — **nunca altere**.
2. Todo APK é assinado com a **mesma chave** (`keystore/inventario.jks`).
3. O `versionCode` é calculado no CI como *minutos desde 2024-01-01*, então sempre cresce.

> **Sobre a chave**: ela está no repositório para tudo funcionar sem configuração.
> Se preferir tirá-la do repositório, crie os secrets `KEYSTORE_BASE64`
> (`base64 -w0 keystore/inventario.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`
> (os três são `inventario`) com **a mesma chave** e depois apague o arquivo.
> Se usar uma chave diferente, será preciso desinstalar uma única vez.

## Backup no S3

Em **Backup** → preencha bucket, região, (endpoint se não for AWS), Access Key e Secret.
São gravados `inventario-AAAA-MM-DD.json` (um por dia, mantendo histórico) e
`inventario-latest.json`. A requisição é assinada (SigV4) no próprio app — não precisa
configurar CORS. Recomendado: usuário IAM com acesso só ao bucket:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject"], "Resource": "arn:aws:s3:::MEU-BUCKET/inventario/*"},
    {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::MEU-BUCKET"}
  ]
}
```

## Estrutura

```
app/src/main/assets/index.html   # o app inteiro (HTML/CSS/JS, sem dependências)
app/src/main/java/.../MainActivity.java  # WebView + ponte: arquivo local, exportar/importar, S3
.github/workflows/android.yml    # build e release do APK
```

O `index.html` também abre direto no navegador do PC (usa `localStorage`; S3 só no app).
Os dados no Android ficam em `db.json` (gravação atômica) dentro da área privada do app.
