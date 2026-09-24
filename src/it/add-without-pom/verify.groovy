assert new File(basedir, '.opencode/skills/demo/SKILL.md').isFile()
assert new File(basedir, 'skill-sommelier.lock.json').text.contains('"target" : "opencode"')
