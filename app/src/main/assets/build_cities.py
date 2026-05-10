import sqlite3, csv

conn = sqlite3.connect('cities.db')
conn.execute('''CREATE TABLE IF NOT EXISTS cities (
    lat REAL, lon REAL, name TEXT, country TEXT
)''')

with open('cities1000.txt', encoding='utf-8') as f:
    for row in csv.reader(f, delimiter='\t'):
        conn.execute('INSERT INTO cities VALUES (?,?,?,?)',
            (float(row[4]), float(row[5]), row[1], row[8]))

conn.execute('CREATE INDEX IF NOT EXISTS idx_lat_lon ON cities(lat, lon)')
conn.commit()

conn.execute('''CREATE TABLE IF NOT EXISTS countries (
    iso TEXT PRIMARY KEY, name TEXT
)''')

with open('countryInfo.txt', encoding='utf-8') as f:
    for line in f:
        if line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) > 4:
            iso = parts[0].strip()
            name = parts[4].strip().lower()
            conn.execute('INSERT OR REPLACE INTO countries VALUES (?,?)', (iso, name))

conn.commit()

conn.close()
print("Done")
