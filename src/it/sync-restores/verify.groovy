assert new File(basedir, '.claude/skills/demo/SKILL.md').isFile()
assert !new File(basedir, 'skill-sommelier.lock.json').text.contains('sha256:outdated')
assert new File(basedir, 'build.log').text.contains('Skill sync: 1 restored')
