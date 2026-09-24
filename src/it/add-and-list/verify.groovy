def skill = new File(basedir, '.claude/skills/demo/SKILL.md')
assert skill.isFile()
def lock = new File(basedir, 'skill-sommelier.lock.json').text
assert lock.contains('"url" : "skills-source"') : 'local source must be recorded relative to the project'
assert !lock.contains('token')
def log = new File(basedir, 'build.log').text
assert log.contains('demo - Demo skill  [installed: claude]')
