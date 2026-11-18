# 줄별 해설 작성 규칙

실제 파일과 줄 번호를 대조해 해설을 작성합니다. 소스 코드는 고치지 않습니다.

각 JSON은 다음 형태를 사용합니다.

```json
{
  "files": {
    "src/main/java/dev/mentoring/orders/service/OrderService.java": {
      "purpose": "주문 접수와 취소 처리",
      "role": "Service",
      "calledBy": ["OrderController"],
      "technologies": ["transaction", "idempotency"],
      "decisions": [
        {
          "title": "주문과 재고를 함께 저장",
          "why": "주문만 저장되거나 재고만 줄어드는 부분 저장을 막습니다.",
          "alternatives": "각 저장을 따로 커밋하면 오류 뒤 일부만 남을 수 있습니다.",
          "limit": "트랜잭션만으로 같은 구매의 중복 요청을 막지는 못합니다."
        }
      ],
      "methods": {
        "placeOrder": {
          "title": "주문 접수",
          "purpose": "같은 구매를 확인하고 신규 요청만 재고와 주문을 저장합니다."
        }
      },
      "lines": {
        "56": "주문 접수 전체에 READ_COMMITTED 트랜잭션을 적용합니다. SQL이 끝난 뒤 커밋까지 성공해야 호출자에게 결과를 돌려줍니다."
      }
    }
  }
}
```

- 경로는 저장소 기준이며 `/`로 구분합니다. 줄 번호는 현재 원문 기준입니다.
- 어노테이션, 조건 분기, SQL, 반환, 외부 호출과 오류 처리는 대상과 효과를 구체적으로 적습니다.
- `package`, `import`, 공백, 중괄호와 기존 주석은 공통 해설로 처리하므로 중복 작성하지 않아도 됩니다.
- 한 줄에 여러 호출이 있으면 각 호출의 목적이 빠지지 않게 설명합니다.
- 메서드 목적과 줄 해설은 다릅니다. 실행 줄마다 같은 메서드 요약을 반복하지 않습니다.
- `technologies`는 `transaction`, `idempotency`, `conditional-update`, `jdbc`, `mvc`, `dto`, `validation`, `security`, `csrf`, `bcrypt`, `admission`, `body-size`, `error-handling`, `flyway`, `testing`, `architecture`, `configuration`, `java`, `build`, `tooling` 중 관련된 항목을 사용합니다.
- 설명은 자연스러운 한국어로 씁니다. 대상과 조건을 생략하지 않고, 굵은 글씨와 가운데점은 쓰지 않습니다.
- 실제 검사 결과, 단위 검사 대역, 제안과 미검증 범위를 구분합니다. 대안의 속도나 최선 여부를 측정 없이 단정하지 않습니다.
