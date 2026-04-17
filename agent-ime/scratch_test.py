# mental test
left_avatar_score = 400
left_bg_score = 100
right_avatar_score = 400
right_bg_score = 100

def check(l_score, r_score):
    return l_score > r_score * 1.2 and l_score > 20

# Inbound
print(check(left_avatar_score + left_bg_score, right_bg_score)) # 500 > 120 -> True

# Outbound
print(check(left_bg_score, right_avatar_score + right_bg_score)) # 100 > 600 -> False
