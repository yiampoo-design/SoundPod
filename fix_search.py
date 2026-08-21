path = r'C:/Users/Yiam/.minimax/workspace/SoundPod/innertube/src/main/kotlin/com/github/innertube/requests/SearchPage.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Add import for applyYouTubeMusicClient
old_imports = 'import com.github.innertube.Innertube'
new_imports = 'import com.github.innertube.Innertube\nimport com.github.innertube.applyYouTubeMusicClient'
content = content.replace(old_imports, new_imports, 1)

# Add applyYouTubeMusicClient call to the first search post block
old_post = '''        client.post(SEARCH) {
            setBody(
                SearchBody(
                    context = client.toContext(
                        hl = hl,
                        gl = gl,
                        visitorData = visitorData,
                    ),
                    query = query,
                    params = params,
                )
            )
            mask("contents.tabbedSearchResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents.musicShelfRenderer(continuations,contents.$MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK)")
        }'''

new_post = '''        client.post(SEARCH) {
            applyYouTubeMusicClient(client, visitorData)
            setBody(
                SearchBody(
                    context = client.toContext(
                        hl = hl,
                        gl = gl,
                        visitorData = visitorData,
                    ),
                    query = query,
                    params = params,
                )
            )
            mask("contents.tabbedSearchResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents.musicShelfRenderer(continuations,contents.$MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK)")
        }'''

if old_post in content:
    content = content.replace(old_post, new_post, 1)
    print('Patched search post block')
else:
    print('ERROR: search post block not found')
    raise SystemExit(1)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Written SearchPage.kt')
