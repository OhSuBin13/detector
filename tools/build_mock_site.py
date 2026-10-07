#!/usr/bin/env python3
"""모의 사이트(mock-site/)를 만든다.

    python tools/build_mock_site.py

정답은 요소의 data-expect 속성에 적는다(공백으로 구분한 기법 코드). 추가 유형(ETC)은 data-expect-extra.
평가 하네스(gradlew evaluate)가 이 속성과 탐지 결과를 비교한다. 속성이 없는 숨김·특수문자 글은 대조군(오탐 확인용)이다.
대조군 중 오탐을 받아들이기로 한 것은 data-known-fp에 이유를 적는다. 오탐으로 세되 --strict 실패로는 보지 않는다.
생성물은 저장소에 올린다. 사례를 고치면 이 스크립트를 고치고 다시 돌린다.
"""
import io
import os
import unicodedata

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'mock-site')

ZW = chr(0x200B)


def fullwidth(s):
    return ''.join(chr(ord(c) + 0xFEE0) if '!' <= c <= '~' else c for c in s)


CYRILLIC = {'a': 0x430, 'c': 0x441, 'e': 0x435, 'o': 0x43E, 'p': 0x440, 'i': 0x456, 's': 0x455, 'x': 0x445, 'y': 0x443}
GREEK_CAPS = {'A': 0x391, 'B': 0x392, 'E': 0x395, 'T': 0x3A4, 'O': 0x39F, 'K': 0x39A}


def cyr(s, letters):
    """s의 글자 중 letters에 든 것만 키릴 닮은꼴로 바꾼다."""
    return ''.join(chr(CYRILLIC[c]) if c in letters and c in CYRILLIC else c for c in s)


def greek(s):
    return ''.join(chr(GREEK_CAPS[c]) if c in GREEK_CAPS else c for c in s)


def math_bold(s):
    return ''.join(chr(0x1D41A + ord(c) - 97) if 'a' <= c <= 'z' else c for c in s)


def circled(s):
    return ''.join(chr(0x24D0 + ord(c) - 97) if 'a' <= c <= 'z' else c for c in s)


# 반각 자모는 호환 자모와 같은 순서로 놓여 있다(모음은 6개씩 끊어져 있음).
HALF = {}
for half, compat, count in ((0xFFA1, 0x3131, 30), (0xFFC2, 0x314F, 6), (0xFFCA, 0x3155, 6), (0xFFD2, 0x315B, 6), (0xFFDA, 0x3161, 3)):
    for k in range(count):
        HALF[chr(compat + k)] = chr(half + k)


def halfwidth_jamo(s):
    return ''.join(HALF.get(c, c) for c in s)


def nfd(s):
    return unicodedata.normalize('NFD', s)


HEADER = '''<a class="sr-only" href="#content">본문 바로가기</a>
<header class="site-header">
  <div class="wrap">
    <a class="site-title" href="/index.html">모의군청</a>
    <nav class="gnb">
      <ul>
        <li><a href="/board/list.html">공지사항</a>
          <div class="gnb-sub"><a href="/board/list.html">공지사항</a> <a href="/news/list.html">보도자료</a></div></li>
        <li><a href="/free/list.html">자유게시판</a>
          <div class="gnb-sub"><a href="/free/list.html">자유게시판</a> <a href="/free/view-1.html">인기 글</a></div></li>
        <li><a href="/guide/homoglyph.html">은닉 기법 예시</a>
          <div class="gnb-sub"><a href="/guide/homoglyph.html">닮은꼴 글자</a> <a href="/guide/jamo.html">자모 분해</a>
            <a href="/guide/transparent.html">투명 텍스트</a> <a href="/guide/offscreen.html">화면 밖 은닉</a>
            <a href="/guide/nested.html">iframe</a> <a href="/guide/extra.html">추가 유형</a></div></li>
        <li><a href="/news/article-2.html">도박문제 예방</a>
          <div class="gnb-sub"><a href="/news/article-2.html">카지노업 현황</a> <a href="/news/article-2.html">사행산업 통계</a>
            <a href="/news/article-1.html">불법도박 신고 안내</a> <a href="/news/article-2.html">예방교육 상담</a></div></li>
      </ul>
    </nav>
  </div>
</header>'''

FOOTER = '''<footer class="site-footer">
  <div class="wrap">
    <p class="addr">(12345) 모의도 모의군 중앙로 1 모의군청 · 대표전화 031-000-0000 · www.mock.go.kr</p>
    <span class="blind">이 누리집은 모의 사이트입니다</span>
    %s
  </div>
</footer>'''


def page(path, title, body, footer_extra='', head_extra=''):
    html = '''<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>%s - 모의군청</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="/css/site.css">
%s</head>
<body>
%s
<main id="content">
  <div class="wrap">
    <h1>%s</h1>
%s
  </div>
</main>
%s
</body>
</html>
''' % (title, head_extra, HEADER, title, body, FOOTER % footer_extra)
    write(path, html)


def write(path, text):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with io.open(full, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def raw(path, title, body, head_extra=''):
    """머리글·바닥글이 없는 문서(iframe 안에 들어가는 것)."""
    write(path, '''<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>%s</title>
<style>body{font-family:"Malgun Gothic",sans-serif;font-size:14px;margin:8px;background:#fff;color:#222}
.hidden-link{position:absolute;top:-9999px;left:-9999px}.promo{display:none}</style>
%s</head>
<body>
%s
</body>
</html>
''' % (title, head_extra, body))


# ───────────────────────── 첫 화면
page('index.html', '모의군청에 오신 것을 환영합니다', '''
    <p class="lead">군정 소식과 민원 안내를 제공합니다. 이 사이트는 불법광고 은닉 탐지 도구를 시험하기 위한 모의 사이트입니다.</p>
    <a class="logo-text" href="/index.html">모의군청</a>
    <ul class="quick">
      <li><a href="/board/list.html">공지사항</a></li>
      <li><a href="/free/list.html">자유게시판</a></li>
      <li><a href="/news/list.html">보도자료</a></li>
      <li><a href="/guide/homoglyph.html">은닉 기법 예시 모음</a></li>
      <li><a href="/files/notice.pdf">10월 군정 소식지 내려받기 (PDF)</a></li>
      <li><a href="/files/form.hwp" download>민원 서식 내려받기</a></li>
      <li><a href="/logout.html">로그아웃</a></li>
    </ul>
    <button type="button" class="btn-event" onclick="location.href='/event.html'">이달의 행사 보기</button>

    <div class="tabs">
      <div class="tab-panel on"><p>오늘의 민원 처리 현황: 접수 42건, 처리 40건</p></div>
      <div class="tab-panel"><p>이번 주 행사: 가을 걷기 대회, 농산물 직거래 장터</p></div>
      <div class="tab-panel"><p>채용 공고: 기간제 근로자 모집 (상담 문의는 총무과)</p></div>
    </div>

    <h2>알림판</h2>
    <iframe class="notice-widget" src="/widget/notice.html" width="600" height="160" title="알림판"></iframe>
''')

raw('widget/notice.html', '알림판', '''<div class="wrap">
  <ul class="notice-items">
    <li><a href="/board/view-1.html" target="_top">가을 걷기 대회 참가자 모집</a></li>
    <li><a href="/board/view-2.html" target="_top">독감 예방접종 안내</a></li>
  </ul>
  <span class="hidden-link" data-expect="OFFSCREEN">무료 슬롯 체험 바로가기</span>
</div>''')

# ───────────────────────── 공지사항
rows = [
    ('view-1.html', '가을 걷기 대회 참가자 모집'),
    ('view-2.html', '독감 예방접종 안내'),
    ('view-3.html', '도로 보수 공사에 따른 통행 제한 알림'),
    ('view-1024.html', '10월 주민자치회 회의 결과'),
    ('view-1180.html', '군립도서관 휴관일 변경 안내'),
    ('view-1.html', '재활용품 분리배출 요령'),
    ('view-2.html', fullwidth('mega-BET') + ' 첫충 30' + fullwidth('%') + ' 지급'),
    ('view-3.html', '농기계 임대 사업소 운영 시간 안내'),
    ('view-1.html', '2026년 하반기 공공근로 모집'),
    ('view-2.html', '수도 요금 납부 방법 안내'),
]
body = ['    <div class="board-list">', '    <table class="board">',
        '      <thead><tr><th>번호</th><th>제목</th><th>작성일</th></tr></thead>', '      <tbody>']
for i, (href, title) in enumerate(rows):
    expect = ' data-expect="HOMOGLYPH"' if i == 6 else ''
    body.append('        <tr><td class="num">%d</td><td class="title"><a href="/board/%s"%s>%s</a></td><td class="date">2026-10-%02d</td></tr>'
                % (20 - i, href, expect, title, 5 - i // 3))
body += ['      </tbody>', '    </table>', '    </div>',
         '    <p class="paging"><strong>1</strong> <a href="/board/list-2.html">2</a></p>',
         '    <p class="admin"><a href="/board/delete.html?id=3">선택 삭제</a> <a href="/board/write.html">글쓰기</a></p>']
page('board/list.html', '공지사항', '\n'.join(body))

rows2 = [
    ('view-3.html', '제설 자재 사전 점검 결과', ''),
    ('view-1.html', 'ㅌㅗㅌㅗ ㅅㅏㅇㅣㅌㅡ 추천', ' data-expect="JAMO"'),
    ('view-2.html', 'ㅋㅋㅋ 오늘 날씨 정말 좋네요 ㅎㅎ', ''),
    ('view-3.html', '마을버스 노선 개편 안내', ''),
]
body = ['    <div class="board-list">', '    <table class="board">', '      <tbody>']
for i, (href, title, expect) in enumerate(rows2):
    body.append('        <tr><td class="num">%d</td><td class="title"><a href="/board/%s"%s>%s</a></td><td class="date">2026-09-%02d</td></tr>'
                % (10 - i, href, expect, title, 28 - i))
body += ['      </tbody>', '    </table>', '    </div>', '    <p class="paging"><a href="/board/list.html">1</a> <strong>2</strong></p>']
page('board/list-2.html', '공지사항 (2쪽)', '\n'.join(body))

page('board/view-1.html', '가을 걷기 대회 참가자 모집', '''
    <div class="article-body">
      <p>10월 셋째 주 토요일, 군민과 함께하는 가을 걷기 대회를 엽니다. 참가를 원하시는 분은 읍·면사무소로 신청해 주세요.</p>
      <p>문의: 문화체육과 031-000-1234</p>
      <p class="attach">첨부: <a href="/files/notice.pdf">대회 안내문.pdf</a></p>
    </div>
    <p><a href="/board/list.html">목록</a></p>
''')
page('board/view-2.html', '독감 예방접종 안내', '''
    <div class="article-body">
      <p>65세 이상 어르신과 어린이는 보건소와 지정 의료기관에서 무료로 접종받을 수 있습니다.</p>
      <p>접종 전 예진표를 작성해 주세요. 이상 반응이 있으면 즉시 신고해 주시기 바랍니다.</p>
    </div>
    <p><a href="/board/list.html">목록</a></p>
''')
page('board/view-3.html', '도로 보수 공사에 따른 통행 제한 알림', '''
    <div class="article-body">
      <p>중앙로 일부 구간이 10월 12일부터 16일까지 통제됩니다. 우회 도로를 이용해 주세요.</p>
    </div>
    <p><a href="/board/list.html">목록</a></p>
''')

page('board/view-1024.html', '10월 주민자치회 회의 결과', '''
    <div class="notice-board">
      <p>10월 주민자치회 정기 회의 결과를 알려 드립니다. 마을 안길 정비와 가로등 교체 안건이 원안대로 의결되었습니다.</p>
      <span class="visually-hidden" data-expect="JAMO OFFSCREEN">ㅋㅏㅈㅣㄴㅗ ㅂㅗㄴㅓㅅㅡ 코드 LUCKY7</span>
    </div>
    <h2>댓글</h2>
    <ul class="comment-list">
      <li class="comment"><span class="writer">주민1</span> <p class="comment-body">가로등이 빨리 교체되면 좋겠습니다.</p></li>
      <li class="comment"><span class="writer">guest77</span> <p class="comment-body" data-expect="HOMOGLYPH">7r지노 가입하면 꽁머니 지급</p></li>
      <li class="comment"><span class="writer">주민2</span> <p class="comment-body">회의록 공개 감사합니다 ㅎㅎ</p></li>
    </ul>
    <p><a href="/board/list.html">목록</a></p>
''')

page('board/view-1180.html', '군립도서관 휴관일 변경 안내', '''
    <div class="article-body">
      <p class="desc">군립도서관 정기 휴관일이 매주 월요일에서 매월 둘째·넷째 월요일로 바뀝니다.
        <span style="color:transparent" data-expect="TRANSPARENT">먹튀검증 안전놀이터 상담 텔레그램 @promo_777</span></p>
      <p>도서 대출 기간은 14일이며 1회 연장할 수 있습니다. 성인 열람실은 22시까지 운영합니다.</p>
    </div>
    <iframe src="../widget/notice.html" width="600" height="160" title="알림판"></iframe>
    <p><a href="/board/list.html">목록</a></p>
''')

# ───────────────────────── 닮은꼴 글자
page('guide/homoglyph.html', '닮은꼴 글자 위장 예시', '''
    <h2>탐지 대상</h2>
    <div class="cases">
      <p class="hg-fullwidth" data-expect="HOMOGLYPH">%(fw)s 첫충 30%(pct)s 지급</p>
      <p class="hg-cyrillic" data-expect="HOMOGLYPH">%(cy)s 신규가입 이벤트</p>
      <p class="hg-greek" data-expect="HOMOGLYPH">%(gr)s 365 우회주소 안내</p>
      <p class="hg-leet" data-expect="HOMOGLYPH">c4s1no 바로가기</p>
      <p class="hg-hangul" data-expect="HOMOGLYPH">7r지노 무료 쿠폰</p>
      <p class="hg-hangul2" data-expect="HOMOGLYPH">바7r라 사이트 주소</p>
      <p class="hg-math" data-expect="HOMOGLYPH">%(mb)s 라이브 중계</p>
      <p class="hg-circled" data-expect="HOMOGLYPH">%(ci)s 추천</p>
      <p class="hg-word" data-expect="HOMOGLYPH">%(lucky)s 카지노 가입코드 문의</p>
      <p class="hg-both" data-expect="HOMOGLYPH JAMO">%(bet)s ㅋㅏㅈㅣㄴㅗ 동시 가입</p>
      <ul class="hg-list">
        <li>정상 항목입니다</li>
        <li data-expect="HOMOGLYPH">%(slot)s 잭팟 당첨 후기</li>
        <li>정상 항목입니다</li>
      </ul>
    </div>

    <h2>대조군 (탐지하면 오탐)</h2>
    <div class="decoys">
      <p>%(kbs)s 뉴스 특보</p>
      <p>할인율 %(d30)s (부가세 포함)</p>
      <p>Привет, это обычный русский текст.</p>
      <p>㈜모의상사 ℡ 02-123-4567 · 면적 30㎡ · 무게 5㎏</p>
      <p>모델명 B3T-100 재고 5개, 품번 SL0T-7</p>
      <p>E스포츠 대회 1등 상금 안내, T머니 충전소</p>
      <p>α선과 β선의 차이, 파장 500㎚</p>
      <p>café 메뉴 안내 · résumé 작성법</p>
      <p>2026년 10월 1일 7시 LG전자 공개 채용 설명회</p>
      <p>%(casino)s 단속 강화, 경찰 불법 도박장 적발</p>
      <p>alphabet, internet, velvet 같은 낱말은 정상입니다. %(alphabet)s</p>
      <p>Ⅰ. 개요 Ⅱ. 추진 계획 Ⅲ. 기대 효과</p>
    </div>
''' % {
    'fw': fullwidth('mega-BET'), 'pct': fullwidth('%'),
    'cy': cyr('casino', 'cai'),
    'gr': greek('BET'),
    'mb': math_bold('casino'),
    'ci': circled('casino'),
    'lucky': fullwidth('LUCKY'),
    'bet': fullwidth('bet'),
    'slot': cyr('slot', 'o') .replace('s', chr(0x455)),
    'kbs': fullwidth('KBS'),
    'd30': fullwidth('30%'),
    'casino': fullwidth('CASINO'),
    'alphabet': fullwidth('alphabet'),
})

# ───────────────────────── 자모 분해
page('guide/jamo.html', '자모 분해 예시', '''
    <h2>탐지 대상</h2>
    <div class="ad-box" data-expect="JAMO">ㅋㅏㅈㅣㄴㅗ 바로가기</div>
    <div class="cases">
      <p data-expect="JAMO">ㅂ.ㅏ.ㅋ.ㅏ.ㄹ.ㅏ 가입 즉시 지급</p>
      <p data-expect="JAMO">ㅋ ㅏ ㅈ ㅣ ㄴ ㅗ 신규 이벤트</p>
      <p data-expect="JAMO">카ㅈㅣ노 꽁머니</p>
      <p data-expect="JAMO">ㅂ 오늘도 좋은 하루 ㅏ 함께 걸어요 ㅋ 가을 하늘 ㅏ 맑은 날씨 ㄹ 산책길 ㅏ 행복한 시간</p>
      <p data-expect="JAMO">ㅋr지노 주소 문의</p>
      <p data-expect="JAMO">ㄹㅏㅋㅏㅂㅏ 첫충 지급</p>
      <p data-expect="JAMO">%(half)s 분석 픽 공유</p>
      <p data-expect="JAMO">ㅁㅓㄱㅌㅟ 검증 완료 업체</p>
      <p data-expect="JAMO">ㅅㅡㄹㄹㅗㅅ 무료 체험</p>
    </div>
    <table class="jamo-table">
      <tr><td>정상 칸</td><td>정상 칸</td></tr>
      <tr><td>정상 칸</td><td data-expect="JAMO">ㅍㅏㅇㅝㅂㅗㄹ 사이트</td></tr>
    </table>

    <h2>대조군 (탐지하면 오탐)</h2>
    <div class="decoys">
      <p>ㅋㅋㅋㅋ 진짜 웃기네요 ㅠㅠ</p>
      <p>ㅇㅋ 내일 봬요 ㄱㅅ</p>
      <p>초성 퀴즈: ㅋㅈㄴ, ㅂㅋㄹ 정답은 무엇일까요?</p>
      <p>한글 자모: ㄱ ㄴ ㄷ ㄹ ㅁ ㅂ ㅅ / ㅏ ㅑ ㅓ ㅕ ㅗ ㅛ</p>
      <p>ㄱ + ㅏ = 가, ㄴ + ㅏ = 나 처럼 자음과 모음을 합쳐 글자를 만듭니다.</p>
      <p>%(nfd)s</p>
      <p>ㅇㅇ 그 토토로 인형 귀엽더라 ㅎㅎ</p>
      <p>ㅠㅠ 포커스가 안 맞아요</p>
    </div>
''' % {
    'half': halfwidth_jamo('ㅌㅗㅌㅗ'),
    'nfd': nfd('조합형으로 저장된 정상 공지사항입니다'),
})

# ───────────────────────── 투명 텍스트
page('guide/transparent.html', '투명 텍스트 예시', '''
    <h2>탐지 대상</h2>
    <div class="cases">
      <p>본문 사이에 <span style="opacity:0" data-expect="TRANSPARENT">카지노 신규 가입 쿠폰</span> 끼워 넣은 글</p>
      <p style="opacity:0.001" data-expect="TRANSPARENT">바카라 사이트 추천 주소</p>
      <p>색을 없앤 <span class="clear-text" data-expect="TRANSPARENT">슬롯 무료 체험 바로가기</span> 글</p>
      <p>알파 0 <span style="color:rgba(0,0,0,0)" data-expect="TRANSPARENT">토토사이트 추천</span> 글</p>
      <p class="white-on-white" data-expect="TRANSPARENT">안전놀이터 먹튀검증 문의</p>
      <div class="navy-box">남색 상자 <span class="same-navy" data-expect="TRANSPARENT">온라인카지노 가입코드 777</span></div>
      <p>거의 흰색 <span style="color:#fefefe" data-expect="TRANSPARENT">파워볼 분석 텔레그램 @pb_master</span></p>
      <div class="ghost" data-expect="TRANSPARENT"><p>첫충 30% 지급</p><p>매충 10% 지급</p></div>
      <p>채움색 투명 <span style="-webkit-text-fill-color:transparent" data-expect="TRANSPARENT">바카라 필승법 문의 카톡 baccara77</span></p>
      <p>두 기법 <span style="opacity:0" data-expect="TRANSPARENT JAMO">ㅋㅏㅈㅣㄴㅗ 추천</span></p>
      <p>필터 <span style="filter:opacity(0)" data-expect="TRANSPARENT">카지노 총판 모집</span></p>
      <p style="color:#ffffff">상위 요소에 색을 준 <b data-expect-none="부모에 걸린 색">경우</b></p>
    </div>
    <div class="inherit-case">
      <p style="color:#fff" data-expect="TRANSPARENT"><span>토토 꽁머니 지급</span> <span>가입 즉시</span></p>
    </div>

    <h2>대조군 (탐지하면 오탐)</h2>
    <div class="decoys">
      <p class="dark-box">행사 안내: 10월 가을 축제 (어두운 바탕의 흰 글자, 잘 보임)</p>
      <p class="dark-box">카지노 첫충 이벤트 (눈에 보이는 글은 은닉 기법이 아님)</p>
      <p class="fade-in">스크롤하면 나타나는 안내 문구입니다.</p>
      <p><span class="gradient-text">함께 만드는 행복한 모의군</span></p>
      <div class="photo-box"><span style="color:#fff">사진 설명: 군청 전경</span></div>
      <p style="color:transparent; text-shadow:0 0 0 #000">그림자로 그린 글자</p>
      <p style="opacity:0">불법 도박 신고는 경찰청 112, 카지노 관련 피해 예방 캠페인</p>
      <p style="opacity:0" data-known-fp="주제어만 있는 짧은 숨김. 받아들인 위험(docs/HIDDEN_INTENT_RESEARCH.md 6.3)">카지노업 현황</p>
      <p style="color:#fff">흰 글자지만 광고가 아닌 숨은 글</p>
    </div>
''')

# ───────────────────────── 화면 밖 은닉
page('guide/offscreen.html', '화면 밖 은닉 예시', '''
    <h2>탐지 대상</h2>
    <div class="cases">
      <span style="position:absolute;left:-9999px" data-expect="OFFSCREEN">카지노 주소 바로가기</span>
      <div style="position:absolute;top:-9999px" data-expect="OFFSCREEN">바카라 사이트 추천</div>
      <p class="seo-text" data-expect="OFFSCREEN">토토사이트 추천 먹튀검증</p>
      <p class="tiny" data-expect="OFFSCREEN">슬롯사이트 무료 쿠폰</p>
      <p class="gone" data-expect="OFFSCREEN">안전놀이터 추천 코드 abc</p>
      <p style="text-indent:-9999px" data-expect="OFFSCREEN">온라인카지노 바로가기</p>
      <div hidden data-expect="OFFSCREEN">꽁머니 지급 이벤트</div>
      <div style="margin-left:-9999px" data-expect="OFFSCREEN">파워볼 사이트 가입</div>
      <div style="transform:translateX(-9999px)" data-expect="OFFSCREEN">홀덤 사이트 추천인 코드</div>
      <div class="gone" data-expect="OFFSCREEN"><a href="http://bad.example.com/">카지노사이트 바로가기</a></div>
      <p style="font-size:0" data-expect="OFFSCREEN HOMOGLYPH">%(fwcasino)s 가입</p>
      <div style="position:absolute;left:99999px" data-expect="OFFSCREEN">카지노 총판 문의</div>
      <div class="gone"><span>정상 안내</span> <span class="gone" data-expect="OFFSCREEN">먹튀 없는 메이저놀이터</span></div>
      <p style="font-size:0.5px" data-expect="OFFSCREEN">릴게임 바다이야기 다운로드</p>
    </div>

    <h2>대조군 (탐지하면 오탐)</h2>
    <div class="decoys">
      <a class="sr-only" href="#content">주 메뉴 건너뛰기</a>
      <button type="button"><span class="blind">메뉴 열기</span>≡</button>
      <a class="logo-text" href="/index.html">모의군청 로고</a>
      <div class="tab-panel"><p>숨겨진 탭: 민원 서식 내려받기, 상담 문의 안내</p></div>
      <ul style="font-size:0"><li style="font-size:15px">글자 크기 0인 목록 안의 정상 항목</li><li style="font-size:15px">둘째 항목</li></ul>
      <div class="gone">경찰, 불법 카지노 운영 일당 검거… 바카라 도박장 단속</div>
      <div class="gone">도박문제 예방 치유 상담 전화 1336</div>
      <div style="position:absolute;left:-9999px">화면 낭독기용 안내: 표의 첫 열은 번호입니다</div>
      <p class="gone">성인 요금 3,000원 · 포커스 그룹 모집 · 타임슬롯 예약</p>
    </div>
''' % {'fwcasino': fullwidth('casino')})

# ───────────────────────── iframe
page('guide/nested.html', 'iframe 안의 은닉 예시', '''
    <p>iframe이 여러 겹이거나 같은 주소의 iframe이 여럿인 경우입니다.</p>
    <div class="frame-box">
      <iframe src="/widget/outer.html" width="620" height="200" title="두 겹 iframe"></iframe>
    </div>
    <div class="frames twin">
      <iframe src="/widget/dup.html" width="300" height="80" title="같은 주소 1"></iframe>
      <iframe src="/widget/dup.html" width="300" height="80" title="같은 주소 2"></iframe>
    </div>
    <div class="frames inline">
      <iframe class="inline-doc" width="300" height="80" title="주소 없는 iframe"
        srcdoc="&lt;p&gt;안내 문구&lt;/p&gt;&lt;p class=&quot;x&quot; style=&quot;display:none&quot; data-expect=&quot;OFFSCREEN&quot;&gt;카지노사이트 추천 주소&lt;/p&gt;"></iframe>
    </div>
    <div style="margin-top:1800px">
      <iframe loading="lazy" src="/widget/lazy.html" width="300" height="80" title="화면 아래 lazy iframe"></iframe>
    </div>
''')
raw('widget/outer.html', '바깥 문서', '''<div class="outer">
  <p class="outer-ad" data-expect="JAMO">ㅂㅏㅋㅏㄹㅏ 사이트 주소</p>
  <iframe src="inner.html" width="560" height="100" title="안쪽"></iframe>
</div>''')
raw('widget/inner.html', '안쪽 문서', '''<div class="box">
  <p>안쪽 문서의 정상 글</p>
  <p class="promo" data-expect="OFFSCREEN">토토사이트 가입머니 지급</p>
</div>''')
raw('widget/dup.html', '같은 주소 문서', '''<div class="dup">
  <span style="color:#fff" data-expect="TRANSPARENT">슬롯사이트 추천 코드</span>
</div>''')
raw('widget/lazy.html', 'lazy 문서', '''<div class="lazy">
  <span class="hidden-link" data-expect="OFFSCREEN">바카라사이트 추천 주소</span>
</div>''')

# ───────────────────────── 추가 유형(ETC)
page('guide/extra.html', '추가 유형 예시', '''
    <p>공모전 4기법의 정의에 들지 않는 은닉·위장입니다. result_extra.json에만 기록합니다.</p>
    <div class="cases">
      <p data-expect-extra="ZERO_WIDTH">카%(zw)s지%(zw)s노 추천 주소</p>
      <p style="visibility:hidden" data-expect-extra="VISIBILITY_HIDDEN">바카라사이트 추천</p>
      <span class="sr-only" data-expect-extra="CLIPPED">토토사이트 추천 코드</span>
      <div style="height:0;overflow:hidden" data-expect-extra="CLIPPED">슬롯사이트 무료 쿠폰</div>
      <p data-expect-extra="KEYBOARD_LAYOUT">zkwlsh 가입 쿠폰</p>
    </div>
    <div class="decoys">
      <p style="visibility:hidden">나중에 보여 줄 안내 문구</p>
      <div style="height:0;overflow:hidden">접힌 안내: 민원 처리 기한은 7일입니다</div>
      <p>english words like window, system, monitor are fine</p>
    </div>
''' % {'zw': ZW})

# ───────────────────────── 보도자료 (대조군)
page('news/list.html', '보도자료', '''
    <ul class="news-list">
      <li><a href="/news/article-1.html">경찰, 자모 분해 수법 불법 도박 광고 일당 검거</a></li>
      <li><a href="/news/article-2.html">도박문제 예방 주간 캠페인 운영</a>
        <span class="tag" style="position:absolute;left:-9999px" data-expect="OFFSCREEN">슬롯사이트 가입 쿠폰 지급</span></li>
    </ul>
''')
page('news/article-1.html', '경찰, 자모 분해 수법 불법 도박 광고 일당 검거', '''
    <div class="article-body">
      <p>모의경찰서는 공공기관 게시판에 불법 도박 사이트 광고를 올린 일당을 검거했다고 밝혔다.</p>
      <p>이들은 ‘ㅋㅏㅈㅣㄴㅗ’, ‘%(fw)s’처럼 자모를 풀어 쓰거나 전각 문자를 섞어 금칙어 단속을 피하는 수법을 썼다.</p>
      <p>또 바카라·토토 사이트 주소를 흰 글씨로 숨겨 검색에만 걸리게 한 혐의도 받는다. 경찰은 피해 신고를 당부했다.</p>
      <p style="display:none">관련 기사: 불법 카지노 광고 단속 강화, 먹튀 피해 예방 수칙</p>
      <p><span style="color:#fff">(편집자 주) 수사 중인 사건으로 일부 내용은 공개하지 않습니다.</span></p>
    </div>
    <p><a href="/news/list.html">목록</a></p>
''' % {'fw': fullwidth('casino')})
page('news/article-2.html', '도박문제 예방 주간 캠페인 운영', '''
    <div class="article-body">
      <p>모의군은 도박문제 예방 주간을 맞아 청소년 대상 예방 교육과 상담을 진행한다.</p>
      <p>카지노업 현황과 사행산업 통계는 사행산업통합감독위원회 자료를 참고했다. 불법 스포츠토토 신고는 1855-0112.</p>
      <div class="tab-panel"><p>카지노, 경마, 복권 등 사행산업 건전화 대책 (불법 도박 근절)</p></div>
    </div>
    <!-- 사행산업 감독 기관형 대조군: 키워드가 든 긴 정상 글을 접어 둔 FAQ 답변과 넘겨 보는 전자책 페이지(실사이트 오탐 사례) -->
    <dl class="faq">
      <dt><button type="button" aria-controls="faq-a1" aria-expanded="false">사행산업 총량제란 무엇인가요?</button></dt>
      <dd id="faq-a1" style="display:none">사행산업 총량은 사행산업이 지나치게 커지지 않도록 업종별 연간 순매출액의 상한을 정해 관리하는 제도입니다.
        대상은 카지노업, 경마, 경륜, 경정, 복권, 체육진흥투표권, 소싸움경기이며 외국인 전용 카지노는 제외합니다.
        위원회는 매년 업종별 매출 실적을 점검하고 다음 해 총량을 정해 사업자에게 알립니다.
        자세한 기준은 사행산업통합감독위원회법 시행령과 위원회 고시를 참고하시기 바랍니다.</dd>
    </dl>
    <div class="book">
      <div class="page-wrapper" style="position:absolute;overflow:hidden;width:600px;height:800px;display:none">모의군 사행산업 안내서 3쪽.
        우리나라 사행산업은 관광진흥법에 따른 카지노업, 한국마사회법에 따른 경마, 경륜·경정법에 따른 경륜과 경정,
        복권 및 복권기금법에 따른 복권, 국민체육진흥법에 따른 체육진흥투표권, 전통 소싸움경기에 관한 법률에 따른 소싸움경기로 나뉩니다.
        각 사업은 소관 부처의 허가를 받아 운영되며, 매출의 일부는 기금으로 적립되어 공익사업에 쓰입니다.
        안내서의 다음 쪽에서는 업종별 매출 규모와 이용자 현황을 소개합니다.</div>
    </div>
    <p><a href="/news/list.html">목록</a></p>
''')

# ───────────────────────── 자유게시판: 바닥글이 변조된 구역
INJECTED = '<div class="footer-links"><a class="partner" style="position:absolute;left:-9999px" href="http://bad.example.com/" data-expect="OFFSCREEN">카지노사이트 추천 바로가기</a></div>'

page('free/list.html', '자유게시판', '''
    <table class="board">
      <tbody>
        <tr><td class="num">3</td><td class="title"><a href="/free/view-1.html">주말 장터 다녀왔어요</a></td></tr>
        <tr><td class="num">2</td><td class="title"><a href="/free/view-2.html">도서관 새 책 추천</a></td></tr>
        <tr><td class="num">1</td><td class="title"><a href="/free/view-3.html">분실물 찾습니다</a></td></tr>
      </tbody>
    </table>
''', INJECTED)
page('free/view-1.html', '주말 장터 다녀왔어요', '''
    <div class="article-body"><p>직거래 장터에서 사과를 샀는데 정말 맛있네요. 다음 주에도 열린다고 합니다.</p></div>
    <h2>댓글</h2>
    <ul class="comment-list">
      <li class="comment"><p class="comment-body">저도 다녀왔어요 ㅎㅎ 사람이 많더라고요</p></li>
      <li class="comment"><p class="comment-body" data-expect="JAMO">ㅂㅏㅋㅏㄹㅏ 필승 전략 공유합니다</p></li>
      <li class="comment" style="display:none" data-expect="OFFSCREEN"><p class="comment-body">토토사이트 가입머니 받아가세요</p></li>
      <li class="comment"><p class="comment-body">다음 주에 또 가야겠네요</p></li>
    </ul>
    <p><a href="/free/list.html">목록</a></p>
''', INJECTED)
page('free/view-2.html', '도서관 새 책 추천', '''
    <div class="article-body">
      <p>이번 달 새로 들어온 책 가운데 역사책이 좋았습니다.
        <span style="color:#ffffff" data-expect="TRANSPARENT HOMOGLYPH">%(slot)s 사이트 무료쿠폰</span></p>
    </div>
    <p><a href="/free/list.html">목록</a></p>
''' % {'slot': fullwidth('slot')}, INJECTED)
page('free/view-3.html', '분실물 찾습니다', '''
    <div class="article-body"><p>군민회관 앞에서 검은색 우산을 잃어버렸습니다. 보신 분은 연락 부탁드립니다.</p></div>
    <p><a href="/free/list.html">목록</a></p>
''', INJECTED)

# ───────────────────────── onclick으로만 갈 수 있는 페이지
page('event.html', '이달의 행사', '''
    <p>가을 걷기 대회, 농산물 직거래 장터, 군민 노래자랑이 열립니다.</p>
    <p class="event-ad" data-expect="JAMO">ㅋㅏㅈㅣㄴㅗ ㅅㅏㅇㅣㅌㅡ 이벤트 참여</p>
''')

# ───────────────────────── 방문하면 안 되는 것들(하네스가 요청 기록으로 확인한다)
page('logout.html', '로그아웃', '<p>로그아웃되었습니다.</p>')
page('board/delete.html', '삭제', '<p>삭제되었습니다.</p>')
page('board/write.html', '글쓰기', '<p>로그인이 필요합니다.</p>')
write('files/notice.pdf', '%PDF-1.4\n% 모의 첨부파일(요청되면 안 된다)\n')
write('files/form.hwp', 'HWP mock (must not be requested)\n')

print('mock-site 생성 완료:', os.path.normpath(ROOT))
